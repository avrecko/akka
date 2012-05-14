package akka.remote.rcl

import akka.actor.{Actor, Props}
import akka.remote.{RemoteActorRef, AkkaRemoteSpec, AbstractRemoteActorMultiJvmSpec}
import akka.testkit._
import akka.dispatch.Await
import akka.pattern.ask

object MsgWithMethodMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 2

  class SomeActor extends Actor with Serializable {
    def receive = {
      case bo: BinaryOperation => sender ! bo.doIt(7, 9)
      case "identify" ⇒ sender ! self
      case _ => // drop it
    }
  }

  trait BinaryOperation extends Serializable {
    def doIt(a: Int, b: Int): Int
  }

  class Node2AddOperation extends BinaryOperation {
    def doIt(a: Int, b: Int) = a + b
  }

  class Node2MultiplyOperation extends BinaryOperation {
    def doIt(a: Int, b: Int) = a * b
  }

  import com.typesafe.config.ConfigFactory

  override def commonConfig = ConfigFactory.parseString( """
    akka {
      loglevel = "WARNING"
      actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
          /service-hello.remote = %s
        }
      }
      remote.transport = akka.remote.netty.rcl.NettyRclRemoteTransport
      remote.netty.message-frame-size = 10 MiB
    }""" format akkaURIs(1))
}

import MsgWithMethodMultiJvmSpec._

class MsgWithMethodMultiJvmNode1 extends AkkaRemoteSpec(nodeConfigs(0)) {

  import MsgWithMethodMultiJvmSpec._

  val nodes = NrOfNodes

  "___" must {
    "___" in {
      barrier("start")
      intercept[ClassNotFoundException] {
        Class.forName("akka.remote.rcl.MsgWithMethodMultiJvmSpec$Node2AddOperation")
      }
      intercept[ClassNotFoundException] {
        Class.forName("akka.remote.rcl.MsgWithMethodMultiJvmSpec$Node2MultiplyOperation")
      }
      barrier("done")
    }
  }
}


class MsgWithMethodMultiJvmNode2 extends AkkaRemoteSpec(nodeConfigs(1)) with DefaultTimeout {

  import MsgWithMethodMultiJvmSpec._

  val nodes = NrOfNodes

  "Remote class loading" must {
    "properly work with messages that contain methods" in {
      // need to make sure Node1 does NOT have Node2* classes on the classpath
      // this requires a bit of heavy lifting since Multi-JVM plugin starts 2 JVMs but both point to the same classpath
      // eagerly initialize the Node2 specific classes
      URLishClassLoaderUtil.initialize(classOf[Node2AddOperation], classOf[Node2MultiplyOperation])
      // delete the classes from the classpath this will make sure Node1 cannot load the classes
      URLishClassLoaderUtil.deleteFromClassPath("MsgWithMethodMultiJvmSpec$Node2", this.getClass.getClassLoader)

      barrier("start")

      val actor = system.actorOf(Props[SomeActor], "service-hello")
      actor.isInstanceOf[RemoteActorRef] must be(true)

      Await.result(actor ? new Node2AddOperation, timeout.duration).asInstanceOf[Int] must equal(16)
      Await.result(actor ? new Node2MultiplyOperation, timeout.duration).asInstanceOf[Int] must equal(63)

      // shut down the actor before we let the other node(s) shut down so we don't try to send
      // "Terminate" to a shut down node
      system.stop(actor)
      barrier("done")
    }
  }
}