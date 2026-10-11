package ai.starlake.quack.ondemand.runtime

import io.fabric8.kubernetes.api.model.Pod

import scala.jdk.CollectionConverters.*

/** Reads a node pod's `qod-node-secrets` projected volume back as `file path -> (Secret, key)`. */
object SecretsVolumeView:

  def files(pod: Pod): Map[String, (String, String)] =
    pod.getSpec.getVolumes.asScala
      .filter(_.getName == KubernetesQuackBackend.SecretsVolumeName)
      .flatMap(v => v.getProjected.getSources.asScala)
      .flatMap { src =>
        val s = src.getSecret
        s.getItems.asScala.map(i => i.getPath -> (s.getName, i.getKey))
      }
      .toMap
