/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.routing

import language.postfixOps
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.immutable
import akka.ConfigurationException
import akka.actor.{ Props, Deploy, Actor, ActorRef }
import akka.actor.UnstartedCell
import akka.testkit.{ ImplicitSender, DefaultTimeout, AkkaSpec }
import akka.pattern.gracefulStop
import com.typesafe.config.Config
import akka.actor.ActorSystem

object ConfiguredLocalRoutingSpec {
  val config = """
    akka {
      actor {
        default-dispatcher {
          executor = "thread-pool-executor"
          thread-pool-executor {
            core-pool-size-min = 8
            core-pool-size-max = 16
          }
        }
        deployment {
          /config {
            router = random-pool
            nr-of-instances = 4
            pool-dispatcher {
              fork-join-executor.parallelism-min = 4
              fork-join-executor.parallelism-max = 4
            }
          }
          /paths {
            router = random-group
            routees.paths = ["/user/service1", "/user/service2"]
          }
          /weird {
            router = round-robin-pool
            nr-of-instances = 3
          }
          "/weird/*" {
            router = round-robin-pool
            nr-of-instances = 2
          }
          /myrouter {
            router = "akka.routing.ConfiguredLocalRoutingSpec$MyRouter"
            foo = bar
          }
        }
      }
    }
  """

  class MyRouter(config: Config) extends CustomRouterConfig {
    override def createRouter(system: ActorSystem): Router = Router(MyRoutingLogic(config))
  }

  final case class MyRoutingLogic(config: Config) extends RoutingLogic {
    override def select(message: Any, routees: immutable.IndexedSeq[Routee]): Routee =
      MyRoutee(config.getString(message.toString))
  }

  final case class MyRoutee(reply: String) extends Routee {
    override def send(message: Any, sender: ActorRef): Unit =
      sender ! reply
  }

  class EchoProps extends Actor {
    def receive = {
      case "get" ⇒ sender() ! context.props
    }
  }

  class SendRefAtStartup(testActor: ActorRef) extends Actor {
    testActor ! self
    def receive = { case _ ⇒ }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConfiguredLocalRoutingSpec extends AkkaSpec(ConfiguredLocalRoutingSpec.config) with DefaultTimeout with ImplicitSender {
  import ConfiguredLocalRoutingSpec._

  def routerConfig(ref: ActorRef): akka.routing.RouterConfig = ref match {
    case r: RoutedActorRef ⇒
      r.underlying match {
        case c: RoutedActorCell ⇒ c.routerConfig
        case _: UnstartedCell   ⇒ awaitCond(r.isStarted, 1 second, 10 millis); routerConfig(ref)
      }
  }

  "RouterConfig" must {

    "be picked up from Props" in {
      val actor = system.actorOf(RoundRobinPool(12).props(routeeProps = Props[EchoProps]), "someOther")
      routerConfig(actor) should ===(RoundRobinPool(12))
      Await.result(gracefulStop(actor, 3 seconds), 3 seconds)
    }

    "be overridable in config" in {
      val actor = system.actorOf(RoundRobinPool(12).props(routeeProps = Props[EchoProps]), "config")
      routerConfig(actor) should ===(RandomPool(nrOfInstances = 4, usePoolDispatcher = true))
      Await.result(gracefulStop(actor, 3 seconds), 3 seconds)
    }

    "use routees.paths from config" in {
      val actor = system.actorOf(RandomPool(12).props(routeeProps = Props[EchoProps]), "paths")
      routerConfig(actor) should ===(RandomGroup(List("/user/service1", "/user/service2")))
      Await.result(gracefulStop(actor, 3 seconds), 3 seconds)
    }

    "be overridable in explicit deployment" in {
      val actor = system.actorOf(FromConfig.props(routeeProps = Props[EchoProps]).
        withDeploy(Deploy(routerConfig = RoundRobinPool(12))), "someOther")
      routerConfig(actor) should ===(RoundRobinPool(12))
      Await.result(gracefulStop(actor, 3 seconds), 3 seconds)
    }

    "be overridable in config even with explicit deployment" in {
      val actor = system.actorOf(FromConfig.props(routeeProps = Props[EchoProps]).
        withDeploy(Deploy(routerConfig = RoundRobinPool(12))), "config")
      routerConfig(actor) should ===(RandomPool(nrOfInstances = 4, usePoolDispatcher = true))
      Await.result(gracefulStop(actor, 3 seconds), 3 seconds)
    }

    "fail with an exception if not correct" in {
      intercept[ConfigurationException] {
        system.actorOf(FromConfig.props())
      }
    }

    "not get confused when trying to wildcard-configure children" in {
      val router = system.actorOf(FromConfig.props(routeeProps = Props(classOf[SendRefAtStartup], testActor)), "weird")
      val recv = Set() ++ (for (_ ← 1 to 3) yield expectMsgType[ActorRef])
      val expc = Set('a', 'b', 'c') map (i ⇒ system.actorFor("/user/weird/$" + i))
      recv should ===(expc)
      expectNoMsg(1 second)
    }

    "support custom router" in {
      val myrouter = system.actorOf(FromConfig.props(), "myrouter")
      myrouter ! "foo"
      expectMsg("bar")
    }

  }

}
