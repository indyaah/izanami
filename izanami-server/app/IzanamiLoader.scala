import akka.actor.ActorSystem
import akka.NotUsed
import akka.stream.scaladsl.Flow
import metrics.Metrics
import cats.effect.IO
import com.codahale.metrics.MetricRegistry
import com.softwaremill.macwire._
import controllers._
import controllers.effect._
import controllers.actions.{AuthAction, AuthContext, SecuredAction, SecuredAuthContext}
import domains.config.{ConfigService, ConfigServiceImpl}
import domains.Import
import domains.abtesting.impl._
import domains.abtesting.{ExperimentVariantEventService, _}
import domains.apikey.{ApikeyService, ApikeyStoreImpl}
import domains.events.Events.IzanamiEvent
import domains.events._
import domains.events.impl.{BasicEventStore, DistributedPubSubEventStore, KafkaEventStore, RedisEventStore}
import domains.feature.{FeatureService, FeatureServiceImpl}
import domains.script.Script.ScriptCache
import domains.script.{GlobalScriptService, GlobalScriptServiceImpl, PlayScriptCache}
import domains.user.{UserService, UserServiceImpl}
import domains.webhook.{WebhookService, WebhookServiceImpl}
import libs.database.Drivers
import env._
import filters.{IzanamiDefaultFilter, OtoroshiFilter}
import handlers.ErrorHandler
import patches.{PatchInstance, Patchs}
import patches.impl.ConfigsPatch
import play.api.ApplicationLoader.Context
import play.api._
import play.api.cache.ehcache.EhCacheComponents
import play.api.http.HttpErrorHandler
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{ActionBuilder, AnyContent, EssentialFilter}
import play.api.routing.Router
import router.Routes
import store.leveldb.DbStores
import store.{Healthcheck, JsonDataStore}
import store.memorywithdb.{CacheEvent, InMemoryWithDbStore}

class IzanamiLoader extends ApplicationLoader {

  implicit val lDbStores: DbStores[IO] = new DbStores[IO]

  def load(context: Context) = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment, context.initialConfiguration, Map.empty)
    }
    new modules.IzanamiComponentsInstances(context).application
  }
}

package object modules {

  class IzanamiComponentsInstances(context: Context)(implicit val lDbStores: DbStores[IO])
      extends BuiltInComponentsFromContext(context)
      with AssetsComponents
      with EhCacheComponents
      with AhcWSComponents {

    Logger.info(s"Starting Izanami with java ${System.getProperty("java.version")}")

    lazy val izanamiConfig: IzanamiConfig = IzanamiConfig(configuration)

    implicit val system: ActorSystem = actorSystem

    Logger.info(s"Configuration: \n$izanamiConfig")

    lazy val metricRegistry: MetricRegistry = wire[MetricRegistry]

    lazy val _env: Env = izanamiConfig.baseURL match {
      case "/" => wire[Env]
      case c =>
        val aFinder: AssetsFinder = assetsFinder
          .withUrlPrefix(s"$c/assets")
        wire[Env].copy(assetsFinder = aFinder)
    }

    val authAction: ActionBuilder[AuthContext, AnyContent] = wire[AuthAction]

    val securedSecuredAuthContext: ActionBuilder[SecuredAuthContext, AnyContent] = wire[SecuredAction]

    lazy val homeController: HomeController = wire[HomeController]

    lazy val authController: AuthControllerEff = wire[AuthControllerEff]

    lazy val drivers: Drivers = wire[Drivers]

    lazy val metrics: Metrics                    = wire[Metrics]
    lazy val metricsController: MetricController = wire[MetricController]

    implicit lazy val scriptCache: ScriptCache[IO] = new PlayScriptCache[IO](defaultCacheApi)

    /* Event store */
    // format: off
    lazy val eventStore: EventStore[IO] = izanamiConfig.events match {
      case InMemoryEvents(_) => new BasicEventStore[IO]
      case KafkaEvents(c) => new KafkaEventStore[IO](environment, actorSystem, izanamiConfig.db.kafka.get, c)
      case RedisEvents(c) => new RedisEventStore[IO](drivers.redisClient.get, c, actorSystem)
      case DistributedEvents(c) => new DistributedPubSubEventStore[IO](configuration.underlying, c, applicationLifecycle)
      case other => throw new IllegalArgumentException(s"Unknown event store $other")
    }
    // format: on

    /* Global script */
    lazy val globalScriptStore: GlobalScriptService[IO] = {
      val conf              = izanamiConfig.globalScript.db
      lazy val eventAdapter = InMemoryWithDbStore.globalScriptEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      val store: GlobalScriptService[IO] = wire[GlobalScriptServiceImpl[IO]]
      Import.importFile(conf, store.importData)
      store
    }

    lazy val globalScripController: GlobalScriptControllerEff = wire[GlobalScriptControllerEff]

    /* Config */
    val configStore: ConfigService[IO] = {
      val conf              = izanamiConfig.config.db
      lazy val eventAdapter = InMemoryWithDbStore.configEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      val store: ConfigService[IO] = wire[ConfigServiceImpl[IO]]
      Import.importFile(conf, store.importData)
      store
    }

    lazy val configController: ConfigControllerEff             = wire[ConfigControllerEff]
    lazy val springConfigController: SpringConfigControllerEff = wire[SpringConfigControllerEff]

    /* Feature */
    lazy val featureStore: FeatureService[IO] = {
      val conf              = izanamiConfig.features.db
      lazy val eventAdapter = InMemoryWithDbStore.featureEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      val store: FeatureService[IO] = wire[FeatureServiceImpl[IO]]
      Import.importFile(conf, store.importData)
      store
    }

    lazy val featureController: FeatureControllerEff = wire[FeatureControllerEff]

    /* Experiment */
    lazy val experimentStore: ExperimentService[IO] = {
      val conf              = izanamiConfig.experiment.db
      lazy val eventAdapter = InMemoryWithDbStore.experimentEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      val store: ExperimentService[IO] = wire[ExperimentServiceImpl[IO]]
      Import.importFile(conf, store.importData)
      store
    }

    lazy val experimentVariantEventStore: ExperimentVariantEventService[IO] = {
      val conf = izanamiConfig.experimentEvent.db
      // format: off

      def getExperimentVariantEventStore(dbType: DbType): ExperimentVariantEventService[IO] = dbType match {
        case InMemory  => ExperimentVariantEventInMemoryService(conf, eventStore)
        case Redis     => ExperimentVariantEventRedisService(conf, drivers.redisClient, eventStore)
        case LevelDB   => ExperimentVariantEventLevelDBService(izanamiConfig.db.leveldb.get, conf, eventStore, applicationLifecycle)
        case Cassandra => ExperimentVariantEventCassandreService(drivers.cassandraClient.get._2, conf, izanamiConfig.db.cassandra.get, eventStore)
        case Elastic   => ExperimentVariantEventElasticService(drivers.elasticClient.get, izanamiConfig.db.elastic.get, conf, eventStore)
        case Mongo    =>  ExperimentVariantEventMongoService(conf, drivers.mongoApi.get, eventStore)
        case Dynamo   =>  ExperimentVariantEventDynamoService(izanamiConfig.db.dynamo.get, drivers.dynamoClient.get, eventStore)
        case _ => throw new IllegalArgumentException("Unsupported store type ")
      }

      val store = conf.`type` match {
        case InMemoryWithDb => getExperimentVariantEventStore(izanamiConfig.db.inMemoryWithDb.get.db)
        case other => getExperimentVariantEventStore(other)
      }
      Import.importFile(conf, store.importData)
      store
      // format: on
    }

    lazy val experimentController: ExperimentControllerEff = wire[ExperimentControllerEff]

    /* Webhook */
    lazy val webhookStore: WebhookService[IO] = {
      lazy val webhookConfig = izanamiConfig.webhook
      lazy val conf          = webhookConfig.db
      lazy val eventAdapter  = InMemoryWithDbStore.webhookEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      lazy val store: WebhookService[IO] = wire[WebhookServiceImpl[IO]]
      Import.importFile(conf, store.importData)
      store
    }

    lazy val webhookController: WebhookControllerEff = wire[WebhookControllerEff]

    /* User */
    lazy val userStore: UserService[IO] = {
      val conf              = izanamiConfig.user.db
      lazy val eventAdapter = InMemoryWithDbStore.userEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      lazy val store: UserService[IO] = wire[UserServiceImpl[IO]]
      Import.importFile(conf, store.importData)
      store
    }

    lazy val userController: UserControllerEff = wire[UserControllerEff]

    /* Apikey */
    lazy val apikeyStore: ApikeyService[IO] = {
      val conf              = izanamiConfig.apikey.db
      lazy val eventAdapter = InMemoryWithDbStore.apikeyEventAdapter
      lazy val dbStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      lazy val store: ApikeyService[IO] = wire[ApikeyStoreImpl[IO]]
      Import.importFile(conf, store.importData)
      store
    }
    lazy val apikeyController: ApikeyControllerEff = wire[ApikeyControllerEff]

    lazy val healthCheck: Healthcheck[IO]                    = wire[Healthcheck[IO]]
    lazy val healthCheckController: HealthCheckControllerEff = wire[HealthCheckControllerEff]

    lazy val eventsController: EventsControllerEff = wire[EventsControllerEff]

    val future = {
      lazy val conf: DbDomainConfig = izanamiConfig.patch.db
      lazy val eventAdapter: Flow[IzanamiEvent, CacheEvent, NotUsed] =
        Flow[IzanamiEvent].mapConcat(_ => List.empty[CacheEvent])
      lazy val jsonStore: JsonDataStore[IO] =
        JsonDataStore[IO](drivers, izanamiConfig, conf, eventStore, eventAdapter, applicationLifecycle)
      lazy val jsonStoreOpt: Option[JsonDataStore[IO]] = Some(jsonStore)
      lazy val configsPatch: ConfigsPatch[IO]          = wire[ConfigsPatch[IO]]

      lazy val allPatchs: Map[Int, PatchInstance[IO]] = Map(1 -> configsPatch)

      lazy val patchs: Patchs[IO] = new Patchs[IO](jsonStoreOpt, allPatchs)
      patchs.run().unsafeToFuture()
    }

    lazy val searchController: SearchControllerEff = wire[SearchControllerEff]

    lazy val backOfficeController: BackOfficeController =
      wire[BackOfficeController]

    lazy val swaggerController: SwaggerController = wire[SwaggerController]

    lazy val httpFilters: Seq[EssentialFilter] = izanamiConfig.filter match {
      case env.Otoroshi(config) =>
        Logger.info("Using otoroshi filter")
        Seq(new OtoroshiFilter[IO](_env, config))
      case env.Default(config) =>
        Logger.info("Using default filter")
        Seq(new IzanamiDefaultFilter[IO](_env, izanamiConfig, config, izanamiConfig.apikey, apikeyStore))
    }

    lazy val router: Router = {
      lazy val prefix: String = izanamiConfig.contextPath
      Logger.info(s"Initializing play router with prefix $prefix")
      wire[Routes].withPrefix(prefix)
    }

    override lazy val httpErrorHandler: HttpErrorHandler =
      new ErrorHandler(environment, configuration, None, Some(router))
  }
}
