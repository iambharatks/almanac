package Entities.Subscriber;

import Entities.Message;

public interface ISubscriber {
    public String getId();
    public void onMessage(Message message);
}
