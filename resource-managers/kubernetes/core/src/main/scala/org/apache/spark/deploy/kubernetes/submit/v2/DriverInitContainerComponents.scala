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
package org.apache.spark.deploy.kubernetes.submit.v2

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.deploy.kubernetes.{SparkPodInitContainerBootstrap, SparkPodInitContainerBootstrapImpl}
import org.apache.spark.deploy.kubernetes.config._
import org.apache.spark.deploy.kubernetes.constants._
import org.apache.spark.deploy.rest.kubernetes.v2.RetrofitClientFactoryImpl

/**
 * Interface that wraps the provision of everything the submission client needs to set up the
 * driver's init-container. This is all wrapped in the same place to ensure that related
 * components are being constructed with consistent configurations with respect to one another.
 */
private[spark] trait DriverInitContainerComponents {

  def provideInitContainerConfigMapBuilder(
      maybeJarsResourceId: Option[String], maybeFilesResourceId: Option[String])
      : SparkInitContainerConfigMapBuilder
  def provideContainerLocalizedFilesResolver(): ContainerLocalizedFilesResolver
  def provideExecutorInitContainerConfiguration(): ExecutorInitContainerConfiguration
  def provideInitContainerSubmittedDependencyUploader(
      driverPodLabels: Map[String, String]): Option[SubmittedDependencyUploader]
  def provideSubmittedDependenciesSecretBuilder(
      maybeJarsResourceSecret: Option[String],
      maybeFilesResourceSecret: Option[String]): Option[SubmittedDependencySecretBuilder]
  def provideInitContainerBootstrap(): SparkPodInitContainerBootstrap
}

private[spark] class DriverInitContainerComponentsProviderImpl(
    sparkConf: SparkConf,
    kubernetesAppId: String,
    sparkJars: Seq[String],
    sparkFiles: Seq[String])
    extends DriverInitContainerComponents {

  private val maybeResourceStagingServerUri = sparkConf.get(RESOURCE_STAGING_SERVER_URI)
  private val resourceStagingServerSslOptions = new SecurityManager(sparkConf)
      .getSSLOptions(RESOURCE_STAGING_SERVER_SSL_NAMESPACE)
  private val jarsDownloadPath = sparkConf.get(INIT_CONTAINER_JARS_DOWNLOAD_LOCATION)
  private val filesDownloadPath = sparkConf.get(INIT_CONTAINER_FILES_DOWNLOAD_LOCATION)
  private val maybeSecretName = maybeResourceStagingServerUri.map { _ =>
    s"$kubernetesAppId-init-secret"
  }
  private val namespace = sparkConf.get(KUBERNETES_NAMESPACE)
  private val configMapName = s"$kubernetesAppId-init-config"
  private val configMapKey = s"$kubernetesAppId-init-config-key"
  private val initContainerImage = sparkConf.get(INIT_CONTAINER_DOCKER_IMAGE)
  private val downloadTimeoutMinutes = sparkConf.get(INIT_CONTAINER_MOUNT_TIMEOUT)

  override def provideInitContainerConfigMapBuilder(
      maybeJarsResourceId: Option[String],
      maybeFilesResourceId: Option[String]): SparkInitContainerConfigMapBuilder = {
    val submittedDependencyConfigPlugin = for {
      stagingServerUri <- maybeResourceStagingServerUri
      jarsResourceId <- maybeJarsResourceId
      filesResourceId <- maybeFilesResourceId
    } yield {
      new SubmittedDependencyInitContainerConfigPluginImpl(
        stagingServerUri,
        jarsResourceId,
        filesResourceId,
        resourceStagingServerSslOptions)
    }
    new SparkInitContainerConfigMapBuilderImpl(
      sparkJars,
      sparkFiles,
      jarsDownloadPath,
      filesDownloadPath,
      configMapName,
      configMapKey,
      submittedDependencyConfigPlugin)
  }

  override def provideContainerLocalizedFilesResolver(): ContainerLocalizedFilesResolver = {
    new ContainerLocalizedFilesResolverImpl(
        sparkJars, sparkFiles, jarsDownloadPath, filesDownloadPath)
  }

  override def provideExecutorInitContainerConfiguration(): ExecutorInitContainerConfiguration = {
    new ExecutorInitContainerConfigurationImpl(
        maybeSecretName,
        INIT_CONTAINER_SECRET_VOLUME_MOUNT_PATH,
        configMapName,
        configMapKey)
  }

  override def provideInitContainerSubmittedDependencyUploader(
      driverPodLabels: Map[String, String]): Option[SubmittedDependencyUploader] = {
    maybeResourceStagingServerUri.map { stagingServerUri =>
      new SubmittedDependencyUploaderImpl(
        kubernetesAppId,
        driverPodLabels,
        namespace,
        stagingServerUri,
        sparkJars,
        sparkFiles,
        resourceStagingServerSslOptions,
        RetrofitClientFactoryImpl)
    }
  }

  override def provideSubmittedDependenciesSecretBuilder(
      maybeJarsResourceSecret: Option[String],
      maybeFilesResourceSecret: Option[String]): Option[SubmittedDependencySecretBuilder] = {
    for {
      secretName <- maybeSecretName
      jarsResourceSecret <- maybeJarsResourceSecret
      filesResourceSecret <- maybeFilesResourceSecret
    } yield {
      new SubmittedDependencySecretBuilderImpl(
        secretName,
        jarsResourceSecret,
        filesResourceSecret,
        resourceStagingServerSslOptions)
    }
  }

  override def provideInitContainerBootstrap(): SparkPodInitContainerBootstrap = {
    val submittedDependencyPlugin = maybeSecretName.map { secret =>
      new SubmittedDependencyInitContainerVolumesPluginImpl(
          secret, INIT_CONTAINER_SECRET_VOLUME_MOUNT_PATH)
    }
    new SparkPodInitContainerBootstrapImpl(
      initContainerImage,
      jarsDownloadPath,
      filesDownloadPath,
      downloadTimeoutMinutes,
      configMapName,
      configMapKey,
      submittedDependencyPlugin)
  }
}
