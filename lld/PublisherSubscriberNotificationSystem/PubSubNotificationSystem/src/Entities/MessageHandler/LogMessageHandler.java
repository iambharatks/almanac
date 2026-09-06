package Entities.MessageHandler;

import Entities.Message;

public class LogMessageHandler implements MessageHandler {
    @Override
    public void onMessage(Message message) {
        System.out.println("Logged Message : "+message.toString());
    }
}
