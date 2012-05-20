package tset;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.io.File;

/**
 * Created with IntelliJ IDEA.
 * User: avrecko
 * Date: 5/11/12
 * Time: 5:53 PM
 * To change this template use File | Settings | File Templates.
 */
public class SystemA {

    public static void main(String[] args) {
        Config config = ConfigFactory.parseString("akka {\n" +
                " loglevel = \"INFO\"\n" +
                "  actor {\n" +
                "    provider = \"akka.remote.RemoteActorRefProvider\"\n" +
                "         deployment {\n" +
                "            /sampleActor {\n" +
                "                remote = \"akka://SystemB@127.0.0.1:2552\"\n" +
                "            }\n" +
                "  }}\n" +
                "  remote {\n" +
                "    transport = \"akka.remote.netty.rcl.BlockingRemoteClassLoaderTransport\"\n" +
                "  log-received-messages = \"true\"\n"+
                "  log-sent-messages = \"true\"\n"+
                "    netty {\n" +
                "      message-frame-size = \"20MiB\"\n" +
                "      hostname = \"127.0.0.1\"\n" +
                "      port = 2551\n" +
                " }\n" +
                " }\n" +
                "}").withFallback(ConfigFactory.parseFile(new File("/Users/avrecko/Projects/akka/akka-remote/src/multi-jvm/scala/akka.conf")));


        ActorSystem systemA = ActorSystem.create("SystemA", config);
        ActorRef sampleActor = systemA.actorOf(new Props(EchoActor.class), "sampleActor");
        sampleActor.tell("Hello");
    }
}
