package tset;

import akka.actor.UntypedActor;

/**
 * Created with IntelliJ IDEA.
 * User: avrecko
 * Date: 5/16/12
 * Time: 1:03 AM
 * To change this template use File | Settings | File Templates.
 */
public class EchoActor extends UntypedActor {

    /**
     * To be implemented by concrete UntypedActor. Defines the message handler.
     */@Override public void onReceive(Object message) {
        System.out.println("message = " + message);
        sender().tell(message);
    }


}
