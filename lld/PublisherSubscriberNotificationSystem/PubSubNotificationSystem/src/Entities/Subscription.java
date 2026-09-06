package Entities;

import Entities.Subscriber.ISubscriber;

public class Subscription implements  Runnable {
    private final Topic topic;
    private final ISubscriber subscriber;
    private long offset;
    // the value updated for this running might lose visibility on Subscription thread, hence VOLATILE!!!
    private volatile boolean running;
    private final static long POLL_TIMEOUT_MILLIES = 1000;

    public Subscription(final Topic topic,final ISubscriber subscriber,final int offset) {
        this.topic = topic;
        this.subscriber = subscriber;
        this.offset = offset;
        this.running = true;
    }


    @Override
    public void run() {
        while(true) {
            if(!running) {
                return;
            }
            try {
                //repoll in case POLL_TIMEOUT_MILLIES over and no new message came
                if(!topic.awaitMessage(offset,POLL_TIMEOUT_MILLIES)) continue;
                // consume the message in case new Message arrived
                Message message = topic.getMessage(offset);
                offset++;
                subscriber.onMessage(message);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void stop(){
        this.running = false;
    }
}
