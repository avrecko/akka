package akka.arquillian.tests.webapp.dummy.actors;

import akka.actor.UntypedActor;
import akka.arquillian.tests.webapp.dummy.classes.BarPong;
import akka.arquillian.tests.webapp.dummy.classes.FooPong;

/**
 * Returns "PONG" on "PING" , return instance of [Foo|Bar]Pong on [Foo|Bar]Ping else it returns what it receives.
 */
public class EchoActor extends UntypedActor {

    @Override
    public void onReceive(Object message) throws Exception {
        if ("PING".equals(message)) {
            getContext().tryReply("PONG");
        } else if (message.getClass().getName().endsWith("BarPing")) {
            getContext().tryReply(new BarPong());
        } else if (message.getClass().getName().endsWith("FooPing")) {
            getContext().tryReply(new FooPong());
        } else {
            getContext().tryReply(message);
        }
    }

}