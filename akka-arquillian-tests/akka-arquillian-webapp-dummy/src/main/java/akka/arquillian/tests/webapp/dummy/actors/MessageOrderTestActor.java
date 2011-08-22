package akka.arquillian.tests.webapp.dummy.actors;

import akka.actor.UntypedActor;

import static akka.arquillian.tests.common.Settings.*;

public class MessageOrderTestActor extends UntypedActor {

    private volatile Integer expecting = MSG_ORDER_TEST_X;
    private volatile Boolean correctOrder = true;

    /**
     * To be implemented by concrete UntypedActor. Defines the message handler.
     */
    @Override
    public void onReceive(Object message) throws Exception {
        if (MSG_ORDER_TEST_CORRECT_ORDER_QUESTION_TOKEN.equals(message)) {
            getContext().tryReply(correctOrder);
            return;
        }
        if (expecting.equals(message)) {
            if (expecting.equals(MSG_ORDER_TEST_MAX_NUMBER_SENT)) expecting = MSG_ORDER_TEST_X;
            else expecting++;
            return;
        }
        if (expecting.equals(MSG_ORDER_TEST_X) && !(message instanceof Number)) {
            expecting = 1;
            return;
        }
        correctOrder = false;
    }


}