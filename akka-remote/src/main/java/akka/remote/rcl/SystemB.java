package akka.remote.rcl;

import akka.actor.ActorSystem;
import akka.actor.Props;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.io.File;

/**
 * Created with IntelliJ IDEA.
 * User: avrecko
 * Date: 5/11/12
 * Time: 5:52 PM
 * To change this template use File | Settings | File Templates.
 */
public class SystemB {

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
                "      port = 2552\n" +
                " }\n" +
                " }\n" +
                "}").withFallback(ConfigFactory.parseFile(new File("/Users/avrecko/Projects/akka/akka-remote/src/multi-jvm/scala/akka.conf")));

        ActorSystem systemB = ActorSystem.create("SystemB", config);
//        ActorRef remoteACtor = systemA.actorOf(new Props(RemoteActorDeploy.class), "sampleActor");
//        remoteACtor.tell(new MsgClass("Hello"));
    }
}
