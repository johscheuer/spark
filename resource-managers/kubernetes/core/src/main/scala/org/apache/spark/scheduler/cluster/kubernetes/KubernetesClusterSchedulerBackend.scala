/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.scheduler.cluster.kubernetes

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import org.apache.commons.io.FilenameUtils

import org.apache.spark.{SparkContext, SparkEnv, SparkException}
import org.apache.spark.deploy.kubernetes.Util
import org.apache.spark.deploy.kubernetes.config._
import org.apache.spark.deploy.kubernetes.constants._
import org.apache.spark.rpc._
import org.apache.spark.scheduler.TaskSchedulerImpl
import org.apache.spark.scheduler.cluster.CoarseGrainedClusterMessages.{RetrieveSparkAppConfig, SparkAppConfig}
import org.apache.spark.scheduler.cluster.CoarseGrainedSchedulerBackend
import org.apache.spark.util.{ThreadUtils, Utils}

private[spark] class KubernetesClusterSchedulerBackend(
    scheduler: TaskSchedulerImpl,
    val sc: SparkContext)
  extends CoarseGrainedSchedulerBackend(scheduler, sc.env.rpcEnv) {

  import KubernetesClusterSchedulerBackend._

  private val EXECUTOR_MODIFICATION_LOCK = new Object
  private val runningExecutorPods = new scala.collection.mutable.HashMap[String, Pod]

  private val executorDockerImage = conf.get(EXECUTOR_DOCKER_IMAGE)
  private val kubernetesNamespace = conf.get(KUBERNETES_NAMESPACE)
  private val executorPort = conf.getInt("spark.executor.port", DEFAULT_STATIC_PORT)
  private val blockmanagerPort = conf
    .getInt("spark.blockmanager.port", DEFAULT_BLOCKMANAGER_PORT)

  private val kubernetesDriverPodName = conf
    .get(KUBERNETES_DRIVER_POD_NAME)
    .getOrElse(
      throw new SparkException("Must specify the driver pod name"))

  case class ShuffleServiceConfig(
    shuffleNamespace: String,
    shuffleLabels: Map[String, String],
    shuffleDirs: Seq[String])

  def readShuffleServiceConfig(): Option[ShuffleServiceConfig] = {
    Utils.isDynamicAllocationEnabled(sc.conf) match {
      case true =>
        val shuffleNamespace = conf.get(KUBERNETES_SHUFFLE_NAMESPACE)
        val parsedShuffleLabels = Util.parseKeyValuePairs(conf
          .get(KUBERNETES_SHUFFLE_LABELS), KUBERNETES_SHUFFLE_LABELS.key,
          "shuffle-labels")

        val shuffleDirs = conf.getOption(KUBERNETES_SHUFFLE_DIR.key) match {
          case Some(s) =>
            s.split(",")

          case _ =>
            Utils.getConfiguredLocalDirs(conf)
        }
        Some(ShuffleServiceConfig(shuffleNamespace, parsedShuffleLabels, shuffleDirs))

      case _ =>
        None
    }
  }

  private val shuffleServiceConfig = readShuffleServiceConfig()

  private val executorMemoryMb = conf.get(org.apache.spark.internal.config.EXECUTOR_MEMORY)
  private val executorMemoryString = conf.get(
    org.apache.spark.internal.config.EXECUTOR_MEMORY.key,
    org.apache.spark.internal.config.EXECUTOR_MEMORY.defaultValueString)

  private val memoryOverheadMb = conf
    .get(KUBERNETES_EXECUTOR_MEMORY_OVERHEAD)
    .getOrElse(math.max((MEMORY_OVERHEAD_FACTOR * executorMemoryMb).toInt,
      MEMORY_OVERHEAD_MIN))
  private val executorMemoryWithOverhead = executorMemoryMb + memoryOverheadMb

  private val executorCores = conf.getOption("spark.executor.cores").getOrElse("1")

  private implicit val requestExecutorContext = ExecutionContext.fromExecutorService(
    ThreadUtils.newDaemonCachedThreadPool("kubernetes-executor-requests"))

  private val kubernetesClient = new DriverPodKubernetesClientProvider(conf, kubernetesNamespace)
    .get

  private val driverPod = try {
    kubernetesClient.pods().inNamespace(kubernetesNamespace).
      withName(kubernetesDriverPodName).get()
  } catch {
    case throwable: Throwable =>
      logError(s"Executor cannot find driver pod.", throwable)
      throw new SparkException(s"Executor cannot find driver pod", throwable)
  }

  override val minRegisteredRatio =
    if (conf.getOption("spark.scheduler.minRegisteredResourcesRatio").isEmpty) {
      0.8
    } else {
      super.minRegisteredRatio
    }

  protected var totalExpectedExecutors = new AtomicInteger(0)

  private val driverUrl = RpcEndpointAddress(
    sc.getConf.get("spark.driver.host"),
    sc.getConf.getInt("spark.driver.port", DEFAULT_DRIVER_PORT),
    CoarseGrainedSchedulerBackend.ENDPOINT_NAME).toString

  private val initialExecutors = getInitialTargetExecutorNumber(1)

  private def getInitialTargetExecutorNumber(defaultNumExecutors: Int = 1): Int = {
    if (Utils.isDynamicAllocationEnabled(conf)) {
      val minNumExecutors = conf.getInt("spark.dynamicAllocation.minExecutors", 0)
      val initialNumExecutors = Utils.getDynamicAllocationInitialExecutors(conf)
      val maxNumExecutors = conf.getInt("spark.dynamicAllocation.maxExecutors", 1)
      require(initialNumExecutors >= minNumExecutors && initialNumExecutors <= maxNumExecutors,
        s"initial executor number $initialNumExecutors must between min executor number " +
          s"$minNumExecutors and max executor number $maxNumExecutors")

      initialNumExecutors
    } else {
      conf.getInt("spark.executor.instances", defaultNumExecutors)
    }
  }

  override def applicationId(): String = conf.get("spark.app.id", super.applicationId())

  override def sufficientResourcesRegistered(): Boolean = {
    totalRegisteredExecutors.get() >= initialExecutors * minRegisteredRatio
  }

  override def start(): Unit = {
    super.start()
    if (!Utils.isDynamicAllocationEnabled(sc.conf)) {
      doRequestTotalExecutors(initialExecutors)
    }
  }

  override def stop(): Unit = {
    // send stop message to executors so they shut down cleanly
    super.stop()

    // then delete the executor pods
    // TODO investigate why Utils.tryLogNonFatalError() doesn't work in this context.
    // When using Utils.tryLogNonFatalError some of the code fails but without any logs or
    // indication as to why.
    try {
      runningExecutorPods.values.foreach(kubernetesClient.pods().delete(_))
    } catch {
      case e: Throwable => logError("Uncaught exception while shutting down controllers.", e)
    }
    try {
      kubernetesClient.close()
    } catch {
      case e: Throwable => logError("Uncaught exception closing Kubernetes client.", e)
    }
  }

  private def allocateNewExecutorPod(): (String, Pod) = {
    val executorId = EXECUTOR_ID_COUNTER.incrementAndGet().toString
    val name = s"${applicationId()}-exec-$executorId"

    // hostname must be no longer than 63 characters, so take the last 63 characters of the pod
    // name as the hostname.  This preserves uniqueness since the end of name contains
    // executorId and applicationId
    val hostname = name.substring(Math.max(0, name.length - 63))

    val selectors = Map(SPARK_EXECUTOR_ID_LABEL -> executorId,
      SPARK_APP_ID_LABEL -> applicationId()).asJava
    val executorMemoryQuantity = new QuantityBuilder(false)
      .withAmount(s"${executorMemoryMb}M")
      .build()
    val executorMemoryLimitQuantity = new QuantityBuilder(false)
      .withAmount(s"${executorMemoryWithOverhead}M")
      .build()
    val executorCpuQuantity = new QuantityBuilder(false)
      .withAmount(executorCores)
      .build()
    val requiredEnv = Seq(
      (ENV_EXECUTOR_PORT, executorPort.toString),
      (ENV_DRIVER_URL, driverUrl),
      (ENV_EXECUTOR_CORES, executorCores),
      (ENV_EXECUTOR_MEMORY, executorMemoryString),
      (ENV_APPLICATION_ID, applicationId()),
      (ENV_EXECUTOR_ID, executorId))
      .map(env => new EnvVarBuilder()
        .withName(env._1)
        .withValue(env._2)
        .build()
      ) ++ Seq(
      new EnvVarBuilder()
        .withName(ENV_EXECUTOR_POD_IP)
        .withValueFrom(new EnvVarSourceBuilder()
          .withNewFieldRef("v1", "status.podIP")
          .build())
        .build()
    )
    val requiredPorts = Seq(
      (EXECUTOR_PORT_NAME, executorPort),
      (BLOCK_MANAGER_PORT_NAME, blockmanagerPort))
      .map(port => {
        new ContainerPortBuilder()
          .withName(port._1)
          .withContainerPort(port._2)
          .build()
      })

    val basePodBuilder = new PodBuilder()
      .withNewMetadata()
        .withName(name)
        .withLabels(selectors)
        .withOwnerReferences()
          .addNewOwnerReference()
          .withController(true)
          .withApiVersion(driverPod.getApiVersion)
          .withKind(driverPod.getKind)
          .withName(driverPod.getMetadata.getName)
          .withUid(driverPod.getMetadata.getUid)
        .endOwnerReference()
      .endMetadata()
      .withNewSpec()
        .withHostname(hostname)
        .addNewContainer()
          .withName(s"executor")
          .withImage(executorDockerImage)
          .withImagePullPolicy("IfNotPresent")
          .withNewResources()
            .addToRequests("memory", executorMemoryQuantity)
            .addToLimits("memory", executorMemoryLimitQuantity)
            .addToRequests("cpu", executorCpuQuantity)
            .addToLimits("cpu", executorCpuQuantity)
          .endResources()
          .withEnv(requiredEnv.asJava)
          .withPorts(requiredPorts.asJava)
        .endContainer()
      .endSpec()

    var resolvedPodBuilder = shuffleServiceConfig.map { config =>
      config.shuffleDirs.foldLeft(basePodBuilder) { (builder, dir) =>
        builder.editSpec()
          .addNewVolume()
            .withName(FilenameUtils.getBaseName(dir))
            .withNewHostPath().withPath(dir)
            .endHostPath()
          .endVolume()
          .editFirstContainer()
            .addNewVolumeMount()
            .withName(FilenameUtils.getBaseName(dir))
            .withMountPath(dir)
            .endVolumeMount()
            .endContainer()
          .endSpec()
      }
    }.getOrElse(basePodBuilder)

    try {
      (executorId, kubernetesClient.pods().create(resolvedPodBuilder.build()))
    } catch {
      case throwable: Throwable =>
        logError("Failed to allocate executor pod.", throwable)
        throw throwable
    }
  }

  override def doRequestTotalExecutors(requestedTotal: Int): Future[Boolean] =
    Future[Boolean] {
      EXECUTOR_MODIFICATION_LOCK.synchronized {
        if (requestedTotal > totalExpectedExecutors.get) {
          logInfo(s"Requesting ${requestedTotal - totalExpectedExecutors.get}"
            + s" additional executors, expecting total $requestedTotal and currently" +
            s" expected ${totalExpectedExecutors.get}")
          for (i <- 0 until (requestedTotal - totalExpectedExecutors.get)) {
            runningExecutorPods += allocateNewExecutorPod()
          }
        }
        totalExpectedExecutors.set(requestedTotal)
      }

      var allPodsReady = true
      for (executorPod <- runningExecutorPods.values) {
        try {
          val pod = kubernetesClient.pods().inNamespace(kubernetesNamespace)
            .withName(executorPod.getMetadata().getName()).get()

          allPodsReady &&= Readiness.isPodReady(pod)
        } catch {
          case throwable: Throwable =>
            logWarning(s"Executor no longer exists.", throwable)
            allPodsReady = false
        }
      }

      allPodsReady
    }

  override def doKillExecutors(executorIds: Seq[String]): Future[Boolean] = Future[Boolean] {
    EXECUTOR_MODIFICATION_LOCK.synchronized {
      for (executor <- executorIds) {
        runningExecutorPods.remove(executor) match {
          case Some(pod) => kubernetesClient.pods().delete(pod)
          case None => logWarning(s"Unable to remove pod for unknown executor $executor")
        }
      }
    }
    true
  }

  override def createDriverEndpoint(properties: Seq[(String, String)]): DriverEndpoint = {
    new KubernetesDriverEndpoint(rpcEnv, properties)
  }

  private class KubernetesDriverEndpoint(rpcEnv: RpcEnv, sparkProperties: Seq[(String, String)])
    extends DriverEndpoint(rpcEnv, sparkProperties) {
    override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
      new PartialFunction[Any, Unit]() {
        override def isDefinedAt(msg: Any): Boolean = {
          msg match {
            case RetrieveSparkAppConfig(executorId) =>
              Utils.isDynamicAllocationEnabled(sc.conf)
            case _ => false
          }
        }

        override def apply(msg: Any): Unit = {
          msg match {
            case RetrieveSparkAppConfig(executorId) =>
              EXECUTOR_MODIFICATION_LOCK.synchronized {
                var resolvedProperties = sparkProperties
                val runningExecutorPod = kubernetesClient
                  .pods()
                  .withName(runningExecutorPods(executorId).getMetadata.getName)
                  .get()
                val nodeName = runningExecutorPod.getSpec.getNodeName
                val shuffleServicePods = kubernetesClient
                  .pods()
                  .inNamespace(shuffleServiceConfig.get.shuffleNamespace)
                  .withLabels(shuffleServiceConfig.get.shuffleLabels.asJava)
                  .list()

                // Filter only those pods which are posting Ready status and on the same
                // node as our executor.
                val readyPods = shuffleServicePods
                  .getItems.asScala.toList.filter(pod => Readiness.isPodReady(pod))
                val colocatedPods = readyPods.filter(_.getSpec.getNodeName == nodeName)

                colocatedPods match {
                  case Nil =>
                    logError(s"Cannot find co-located shuffle pod on node ${nodeName}")
                    throw new SparkException(s"Cannot find co-located shuffle pod on node " +
                      s"${nodeName}")

                  case shufflePod :: Nil =>
                    val shuffleIP = conf
                      .get(KUBERNETES_SHUFFLE_SVC_IP)
                      .getOrElse(shufflePod.getStatus.getPodIP)

                    resolvedProperties = resolvedProperties ++ Seq(
                      (SPARK_SHUFFLE_SERVICE_HOST.key, shuffleIP))

                  case _ =>
                    val podsList = colocatedPods.map(_.getStatus.getPodIP).mkString(",")
                    logError(s"Ambiguous specification of shuffle service pod. " +
                      s"Found multiple matching pods: ${podsList}")
                    throw new SparkException(s"Ambiguous specification of shuffle service pod. " +
                      s"Found multiple matching pods: ${podsList}")

                }

                val reply = SparkAppConfig(resolvedProperties,
                  SparkEnv.get.securityManager.getIOEncryptionKey())
                context.reply(reply)
              }
          }
        }
      }.orElse(super.receiveAndReply(context))
    }
  }

}


private object KubernetesClusterSchedulerBackend {
  private val DEFAULT_STATIC_PORT = 10000
  private val EXECUTOR_ID_COUNTER = new AtomicLong(0L)
}
