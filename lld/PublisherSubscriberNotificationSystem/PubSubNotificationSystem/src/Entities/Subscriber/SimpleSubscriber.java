package Entities.Subscriber;

import Entities.Message;
import Entities.MessageHandler.MessageHandler;

import java.util.UUID;

public class SimpleSubscriber implements ISubscriber{
    private final String subscriberId;
    private final MessageHandler messageHandler;

    public SimpleSubscriber(String subscriberId, MessageHandler messageHandler){
        this.subscriberId = subscriberId;
        this.messageHandler = messageHandler;
    }
    @Override
    public String getId() {
        return subscriberId;
    }

    @Override
    public void onMessage(Message message) {
        System.out.println("Message received by subscriber " + subscriberId);
        messageHandler.onMessage(message);
    }
}
