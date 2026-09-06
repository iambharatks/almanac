package Entities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class Topic {
    private String topicName;
    private final List<Message> messageList;
    private int baseOffset;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition hasNewData = lock.newCondition();

    public Topic(final String topicName) {
        this.topicName = topicName;
        this.messageList = new ArrayList<>();
        this.baseOffset = 0;
    }

    public Message getMessage(long offset){
        lock.lock();
        try {
            Message message = null;
            if(offset < baseOffset) {
                System.out.println("Out of bound offset"+offset+" for baseOffset"+baseOffset);
                return message;
            }
            int index = (int)offset-baseOffset;
            if(index >= messageList.size()) {
                System.out.println("Out of bound offset"+offset+" for baseOffset"+baseOffset);
                return message;
            }
            return messageList.get(index);
        }finally {
            lock.unlock();
        }
    }

    public void appendMessage(final Message message){
        lock.lock();
        try{
            messageList.add(message);
            hasNewData.signalAll();
        }finally {
            lock.unlock();
        }
    }

    public boolean awaitMessage(long offset, long timeoutInMillis) throws InterruptedException {
        lock.lock();
        try{
            long deadline =  System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutInMillis) ;
            while(true){
                if(offset < baseOffset + messageList.size()){
                    //New message arrived
                    return true;
                }
                long remaining =  deadline - System.nanoTime();
                // poll time exceeded, repoll
                if(remaining < 0) return false;
                //unlock and park the subscription thread till deadline over or a new Message arrives
                hasNewData.awaitNanos(remaining);
            }
        }finally {
            lock.unlock();
        }
    }
}
