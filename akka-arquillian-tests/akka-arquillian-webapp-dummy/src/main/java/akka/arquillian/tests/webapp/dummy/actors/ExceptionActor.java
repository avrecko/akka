package akka.arquillian.tests.webapp.dummy.actors;

import akka.actor.UntypedActor;
import akka.arquillian.tests.webapp.dummy.classes.DummyException;

/**
 * Created by IntelliJ IDEA.
 * User: avrecko
 * Date: 8/22/11
 * Time: 8:55 AM
 * To change this template use File | Settings | File Templates.
 */
public class ExceptionActor extends UntypedActor {

    /**
     * To be implemented by concrete UntypedActor. Defines the message handler.
     */
    @Override
    public void onReceive(Object message) throws Exception {
        if (message.equals("java")) {
            getContext().tryReply(new RuntimeException("java"));
        } else if (message.equals("rcl")) {
            getContext().tryReply(new DummyException("rcl"));
        } else {
            getContext().tryReply(new RuntimeException("ignored"));
        }
    }
}
