/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.configuration.loader.distributed

import cats.data.EitherT
import monix.eval.Task
import tech.beshu.ror.configuration.loader.{LoadRawRorConfig, LoadedConfig}
import tech.beshu.ror.configuration.{Compiler, ConfigLoading, IndexConfigManager, RawRorConfig}
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.providers.EnvVarsProvider

object RawRorConfigLoadingAction{
  def load(esConfigPath: java.nio.file.Path,
           indexJsonContentService: IndexJsonContentService)
          (implicit envVarsProvider: EnvVarsProvider): Task[Either[LoadedConfig.Error, LoadedConfig[RawRorConfig]]] = {
    val compiler = Compiler.create(new IndexConfigManager(indexJsonContentService))
    (for {
      esConfig <- EitherT(ConfigLoading.loadEsConfig(esConfigPath))
      loadedConfig <- EitherT(LoadRawRorConfig.load(esConfigPath, esConfig, esConfig.rorIndex.index))
    } yield loadedConfig).value.foldMap(compiler)
  }

}
