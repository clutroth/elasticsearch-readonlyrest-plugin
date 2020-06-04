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
package tech.beshu.ror.boot

import java.nio.file.{Path, Paths}
import java.time.Clock

import cats.data.EitherT
import cats.effect.Resource
import cats.implicits._
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.Scheduler.{global => scheduler}
import monix.execution.atomic.Atomic
import monix.execution.{Cancelable, Scheduler}
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason
import tech.beshu.ror.accesscontrol.factory.consts.RorProperties
import tech.beshu.ror.accesscontrol.factory.consts.RorProperties.RefreshInterval
import tech.beshu.ror.accesscontrol.factory.{AsyncHttpClientsFactory, CoreFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.accesscontrol.logging.{AccessControlLoggingDecorator, AuditingTool, LoggingContext}
import tech.beshu.ror.accesscontrol.{AccessControl, AccessControlStaticContext}
import tech.beshu.ror.configuration.EsConfig.LoadEsConfigError
import tech.beshu.ror.configuration.IndexConfigManager.SavingIndexConfigError
import tech.beshu.ror.configuration._
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError
import tech.beshu.ror.configuration.loader.ConfigLoader.ConfigLoaderError._
import tech.beshu.ror.configuration.loader.{ComposedConfigLoader, LoadedConfig}
import tech.beshu.ror.es.{AuditSink, IndexJsonContentManager}
import tech.beshu.ror.providers._
import tech.beshu.ror.utils.ScalaOps.value

import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}

object Ror extends ReadonlyRest {

  val blockingScheduler: Scheduler= Scheduler.io("blocking-index-content-provider")

  override protected val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
  override protected implicit val propertiesProvider: PropertiesProvider = JvmPropertiesProvider
  override protected implicit val clock: Clock = Clock.systemUTC()

  override protected val coreFactory: CoreFactory = {
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val envVarsProviderImplicit: EnvVarsProvider = envVarsProvider
    new RawRorConfigBasedCoreFactory
  }
}


trait ReadonlyRest extends Logging {

  protected def coreFactory: CoreFactory
  protected def envVarsProvider: EnvVarsProvider
  protected implicit def propertiesProvider: PropertiesProvider
  protected implicit def clock: Clock

  def start(esConfigPath: Path,
            auditSink: AuditSink,
            indexContentManager: IndexJsonContentManager)
           (implicit envVarsProvider:EnvVarsProvider): Task[Either[StartingFailure, RorInstance]] = {
    (for {
      esConfig <- loadEsConfig(esConfigPath)
      loadedConfig <- loadConfig(esConfigPath, indexContentManager)
      indexConfigLoader <- createIndexConfigLoader(indexContentManager, esConfigPath)
      instance <- startRor(esConfig, loadedConfig, indexConfigLoader, auditSink)
    } yield instance).value
  }
  private def loadConfig(esConfigPath: Path,
                         indexContentManager: IndexJsonContentManager)
                        (implicit envVarsProvider: EnvVarsProvider)= {
    EitherT(new ComposedConfigLoader(esConfigPath, indexContentManager).load())
      .leftMap(convertError)
  }
  private def convertError(error: LoadedConfig.Error) = {
    error match {
      case LoadedConfig.FileParsingError(message) =>
        StartingFailure(message)
      case LoadedConfig.FileNotExist(path) =>
        StartingFailure(s"Cannot find settings file: ${path.value}")
      case LoadedConfig.EsFileNotExist(path) =>
        StartingFailure(s"Cannot find elasticsearch settings file: [${path.value}]")
      case LoadedConfig.EsFileMalformed(path, message) =>
        StartingFailure(s"Settings file is malformed: [${path.value}], $message")
      case LoadedConfig.EsIndexConfigurationMalformed(message) =>
        StartingFailure(message)
      case LoadedConfig.IndexParsingError(message) =>
        StartingFailure(message)
    }
  }

  private def createIndexConfigLoader(indexContentManager: IndexJsonContentManager, esConfigPath: Path) = {
    for {
      rorIndexNameConfig <- EitherT(RorIndexNameConfiguration.load(esConfigPath)).leftMap(ms => StartingFailure(ms.message))
      indexConfigManager <- EitherT.pure[Task, StartingFailure](new IndexConfigManager(indexContentManager, rorIndexNameConfig))
    } yield indexConfigManager
  }

  private def loadEsConfig(esConfigPath: Path)
                          (implicit envVarsProvider:EnvVarsProvider) = {
    EitherT {
      EsConfig
        .from(esConfigPath)
        .map(_.left.map {
          case LoadEsConfigError.FileNotFound(file) =>
            StartingFailure(s"Cannot find elasticsearch settings file: [${file.pathAsString}]")
          case LoadEsConfigError.MalformedContent(file, msg) =>
            StartingFailure(s"Settings file is malformed: [${file.pathAsString}], $msg")
        })
    }
  }

  private def startRor(esConfig: EsConfig,
                        loadedConfig: LoadedConfig[RawRorConfig],
                       indexConfigManager: IndexConfigManager,
                       auditSink: AuditSink) = {

    for {
      engine <- EitherT(loadRorCore(loadedConfig.value, esConfig.rorIndex, auditSink))
      rorInstance <- createRorInstance(indexConfigManager, auditSink, engine, loadedConfig)
    } yield rorInstance
  }

  private def createRorInstance(indexConfigManager: IndexConfigManager, auditSink: AuditSink, engine: Engine, loadedConfig: LoadedConfig[RawRorConfig]) = {
    EitherT.right[StartingFailure] {
      loadedConfig match {
        case LoadedConfig.FileRecoveredConfig(config, _) =>
          RorInstance.createWithPeriodicIndexCheck(this, engine, config, indexConfigManager, auditSink)
        case LoadedConfig.ForcedFileConfig(config) =>
          RorInstance.createWithoutPeriodicIndexCheck(this, engine, config, indexConfigManager, auditSink)
        case LoadedConfig.IndexConfig(_, config) =>
          RorInstance.createWithPeriodicIndexCheck(this, engine, config, indexConfigManager, auditSink)
      }
    }
  }

  private[ror] def loadRorCore(config: RawRorConfig,
                               rorIndexNameConfiguration: RorIndexNameConfiguration,
                               auditSink: AuditSink): Task[Either[StartingFailure, Engine]] = {
    val httpClientsFactory = new AsyncHttpClientsFactory
    val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider
    coreFactory
      .createCoreFrom(config, rorIndexNameConfiguration, httpClientsFactory, ldapConnectionPoolProvider)
      .map { result =>
        result
          .right
          .map { coreSettings =>
            implicit val loggingContext = LoggingContext(coreSettings.aclStaticContext.obfuscatedHeaders)
            val engine = new Engine(
              accessControl = new AccessControlLoggingDecorator(
                            underlying = coreSettings.aclEngine,
                            auditingTool = coreSettings.auditingSettings.map(new AuditingTool(_, auditSink))
                          ), context = coreSettings.aclStaticContext, httpClientsFactory = httpClientsFactory, ldapConnectionPoolProvider)
            engine
          }
          .left
          .map { errors =>
            val errorsMessage = errors
              .map(_.reason)
              .map {
                case Reason.Message(msg) => msg
                case Reason.MalformedValue(yamlString) => s"Malformed settings: $yamlString"
              }
              .toList
              .mkString("Errors:\n", "\n", "")
            StartingFailure(errorsMessage)
          }
      }
  }

}

class RorInstance private(boot: ReadonlyRest,
                          mode: RorInstance.Mode,
                          initialEngine: (Engine, RawRorConfig),
                          reloadInProgress: Semaphore[Task],
                          indexConfigManager: IndexConfigManager,
                          auditSink: AuditSink)
                          (implicit propertiesProvider: PropertiesProvider)
  extends Logging {

  import RorInstance.ScheduledReloadError.{EngineReloadError, ReloadingInProgress}
  import RorInstance._

  logger.info ("Readonly REST plugin core was loaded ...")
  mode match {
    case Mode.WithPeriodicIndexCheck =>
      RorProperties.rorIndexSettingReloadInterval match {
        case Some(RefreshInterval.Disabled) =>
          logger.info(s"[CLUSTERWIDE SETTINGS] Scheduling in-index settings check disabled")
        case Some(RefreshInterval.Enabled(interval)) =>
          scheduleIndexConfigChecking(interval)
        case None =>
          scheduleIndexConfigChecking(RorInstance.indexConfigCheckingSchedulerDelay)
      }
    case Mode.NoPeriodicIndexCheck => Cancelable.empty
  }

  private val currentEngine = Atomic(Option(initialEngine))

  def engine: Option[Engine] = currentEngine.get().map(_._1)

  def forceReloadAndSave(config: RawRorConfig): Task[Either[IndexConfigReloadWithUpdateError, Unit]] = {
    logger.debug("Reloading of provided settings was forced")
    reloadInProgress.withPermit {
      value {
        for {
          _ <- reloadEngine(config).leftMap(IndexConfigReloadWithUpdateError.ReloadError.apply)
          _ <- saveConfig(config)
        } yield ()
      }
    }
  }

  private def saveConfig(newConfig: RawRorConfig): EitherT[Task, IndexConfigReloadWithUpdateError, Unit] = EitherT {
    for {
      saveResult <- indexConfigManager.save(newConfig)
    } yield saveResult.left.map(IndexConfigReloadWithUpdateError.IndexConfigSavingError.apply)
  }

  def forceReloadFromIndex(): Task[Either[IndexConfigReloadError, Unit]] = {
    reloadInProgress.withPermit {
      logger.debug("Reloading of in-index settings was forced")
      reloadEngineUsingIndexConfig().value
    }
  }

  def stop(): Task[Unit] = {
    reloadInProgress.withPermit {
      Task {
        currentEngine.get().foreach { case (engine, _) => engine.shutdown() }
      }
    }
  }

  private def scheduleIndexConfigChecking(interval: FiniteDuration): Cancelable = {
    logger.debug(s"[CLUSTERWIDE SETTINGS] Scheduling next in-index settings check within $interval")
    scheduler.scheduleOnce(interval) {
      logger.debug("[CLUSTERWIDE SETTINGS] Loading ReadonlyREST config from index ...")
      tryEngineReload()
        .runAsync {
          case Right(Right(_)) =>
            scheduleIndexConfigChecking(interval)
          case Right(Left(ReloadingInProgress)) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS] Reloading in progress ... skipping")
            scheduleIndexConfigChecking(interval)
          case Right(Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.ConfigUpToDate)))) =>
            logger.debug("[CLUSTERWIDE SETTINGS] Settings are up to date. Nothing to reload.")
            scheduleIndexConfigChecking(interval)
          case Right(Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.RorInstanceStopped)))) =>
            logger.debug("[CLUSTERWIDE SETTINGS] Stopping periodic settings check - application is being stopped")
          case Right(Left(EngineReloadError(IndexConfigReloadError.ReloadError(RawConfigReloadError.ReloadingFailed(startingFailure))))) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS] ReadonlyREST starting failed: ${startingFailure.message}")
            scheduleIndexConfigChecking(interval)
          case Right(Left(EngineReloadError(IndexConfigReloadError.LoadingConfigError(error)))) =>
            logger.debug(s"[CLUSTERWIDE SETTINGS] Loading config from index failed: ${error.show}")
            scheduleIndexConfigChecking(interval)
          case Left(ex) =>
            logger.error("[CLUSTERWIDE SETTINGS] Checking index settings failed: error", ex)
            scheduleIndexConfigChecking(interval)
        }
    }
  }

  private def tryEngineReload() = {
    val criticalSection = Resource.make(reloadInProgress.tryAcquire) {
      case true => reloadInProgress.release
      case false => Task.unit
    }
    criticalSection.use {
      case true => value {
        reloadEngineUsingIndexConfig().leftMap(ScheduledReloadError.EngineReloadError.apply)
      }
      case false =>
        Task.now(Left(ScheduledReloadError.ReloadingInProgress))
    }
  }

  private def reloadEngineUsingIndexConfig() = {
    for {
      newConfig <- EitherT(loadRorConfigFromIndex())
      _ <- reloadEngine(newConfig)
        .leftMap(IndexConfigReloadError.ReloadError.apply)
        .leftWiden[IndexConfigReloadError]
    } yield ()
  }

  private def reloadEngine(newConfig: RawRorConfig) = {
    for {
      _ <- shouldBeReloaded(newConfig)
      newEngine <- reloadWith(newConfig)
      oldEngine <- replaceCurrentEngine(newEngine, newConfig)
      _ <- scheduleDelayedShutdown(oldEngine)
    } yield ()
  }

  private def loadRorConfigFromIndex() = {
    indexConfigManager
      .load()
      .map(_.left.map(IndexConfigReloadError.LoadingConfigError.apply))
  }

  private def shouldBeReloaded(config: RawRorConfig): EitherT[Task, RawConfigReloadError, Unit] = {
    currentEngine.get() match {
      case Some((_, currentConfig)) =>
        EitherT.cond[Task](
          currentConfig != config,
          (),
          RawConfigReloadError.ConfigUpToDate
        )
      case None =>
        EitherT.leftT[Task, Unit](RawConfigReloadError.RorInstanceStopped)
    }
  }

  private def reloadWith(config: RawRorConfig): EitherT[Task, RawConfigReloadError, Engine] = EitherT {
    tryToLoadRorCore(config)
      .map(_.leftMap(RawConfigReloadError.ReloadingFailed.apply))
  }

  private def replaceCurrentEngine(newEngine: Engine,
                                   newEngineConfig: RawRorConfig): EitherT[Task, RawConfigReloadError, Engine] = {
    currentEngine
      .getAndTransform {
        _.map(_ => (newEngine, newEngineConfig))
      } match {
      case Some((engine, _)) => EitherT.rightT[Task, RawConfigReloadError](engine)
      case None => EitherT.leftT[Task, Engine](RawConfigReloadError.RorInstanceStopped)
    }
  }

  private def scheduleDelayedShutdown(engine: Engine) = {
    EitherT.right[RawConfigReloadError](Task.now {
      scheduler.scheduleOnce(RorInstance.delayOfOldEngineShutdown) {
        engine.shutdown()
      }
    })
  }

  private def tryToLoadRorCore(config: RawRorConfig) =
    boot.loadRorCore(config, indexConfigManager.rorIndexNameConfiguration, auditSink)
}

object RorInstance {

  sealed trait RawConfigReloadError
  object RawConfigReloadError {
    final case class ReloadingFailed(failure: StartingFailure) extends RawConfigReloadError
    object ConfigUpToDate extends RawConfigReloadError
    object RorInstanceStopped extends RawConfigReloadError
  }

  sealed trait IndexConfigReloadWithUpdateError
  object IndexConfigReloadWithUpdateError {
    final case class ReloadError(undefined: RawConfigReloadError) extends IndexConfigReloadWithUpdateError
    final case class IndexConfigSavingError(underlying: SavingIndexConfigError) extends IndexConfigReloadWithUpdateError
  }

  sealed trait IndexConfigReloadError
  object IndexConfigReloadError {
    final case class LoadingConfigError(underlying: ConfigLoaderError[IndexConfigManager.IndexConfigError]) extends IndexConfigReloadError
    final case class ReloadError(underlying: RawConfigReloadError) extends IndexConfigReloadError
  }

  private sealed trait ScheduledReloadError
  private object ScheduledReloadError {
    case object ReloadingInProgress extends ScheduledReloadError
    final case class EngineReloadError(underlying: IndexConfigReloadError) extends ScheduledReloadError
  }

  def createWithPeriodicIndexCheck(boot: ReadonlyRest,
                                   engine: Engine,
                                   config: RawRorConfig,
                                   indexConfigManager: IndexConfigManager,
                                   auditSink: AuditSink)
                                  (implicit propertiesProvider: PropertiesProvider): Task[RorInstance] = {
    create(boot, Mode.WithPeriodicIndexCheck, engine, config, indexConfigManager, auditSink)
  }

  def createWithoutPeriodicIndexCheck(boot: ReadonlyRest,
                                      engine: Engine,
                                      config: RawRorConfig,
                                      indexConfigManager: IndexConfigManager,
                                      auditSink: AuditSink)
                                     (implicit propertiesProvider: PropertiesProvider): Task[RorInstance] = {
    create(boot, Mode.NoPeriodicIndexCheck, engine, config, indexConfigManager, auditSink)
  }

  private def create(boot: ReadonlyRest,
                     mode: RorInstance.Mode,
                     engine: Engine,
                     config: RawRorConfig,
                     indexConfigManager: IndexConfigManager,
                     auditSink: AuditSink)
                    (implicit propertiesProvider: PropertiesProvider) = {
    Semaphore[Task](1)
      .map { isReloadInProgressSemaphore =>
        new RorInstance(boot, mode, (engine, config), isReloadInProgressSemaphore, indexConfigManager, auditSink)
      }
  }

  private sealed trait Mode
  private object Mode {
    case object WithPeriodicIndexCheck extends Mode
    case object NoPeriodicIndexCheck extends Mode
  }

  private val indexConfigCheckingSchedulerDelay = 5 second
  private val delayOfOldEngineShutdown = 10 seconds
}

final case class StartingFailure(message: String, throwable: Option[Throwable] = None)

final class Engine(val accessControl: AccessControl,
                   val context: AccessControlStaticContext,
                   httpClientsFactory: AsyncHttpClientsFactory,
                   ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider) {

  private [ror] def shutdown(): Unit = {
    httpClientsFactory.shutdown()
    ldapConnectionPoolProvider.close().runAsyncAndForget
  }
}


