package Entities;

import Entities.Subscriber.ISubscriber;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Broker {
    private final Map<String,Topic> topics;
    private final Map<SubscriptionKey, Subscription> subscriptions;
    private final ExecutorService subscriberExecutor;

    public Broker(){
        topics = new ConcurrentHashMap<>();
        subscriptions = new ConcurrentHashMap<>();
        subscriberExecutor = Executors.newCachedThreadPool(
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    return thread;
                }
        );
//        subscriberExecutor = Executors.newVirtualThreadPerTaskExecutor(
//                runnable -> {
//                    Thread thread = new Thread(runnable);
//                    thread.setDaemon(true);
//                    return thread;
//                }
//        );
    }

    public Optional<Topic> createTopic(String topicName){
        if(topics.containsKey(topicName)){
            System.out.println("Topic already exists!!!");
            return Optional.empty();
        }
        Topic topic = new Topic(topicName);
        topics.put(topicName,topic);
        return Optional.of(topic);
    }

    public boolean publish(IPublisher publisher, String topicName, Message message){
        Topic topic = topics.get(topicName);
        if(topic == null){
            System.out.println("Topic not found!!!" );
            return false;
        }
        topic.appendMessage(message);
        return true;
    }
    // no need to add thread safety for this function not required
    public boolean subscribe(String topicName, ISubscriber subscriber){
        Topic topic = topics.get(topicName);
        if(topic == null){
            System.out.println("Topic not found!!!" );
            return false;
        }
        Subscription subscription = new Subscription(topic,subscriber,0);
        subscriptions.put(new SubscriptionKey(topicName,subscriber.getId()),subscription);
        subscriberExecutor.submit(subscription);
        return true;
    }

    // stop the thread and remove it
    public boolean unsubscribe(String topicName, ISubscriber subscriber){
        Topic topic = topics.get(topicName);
        if(topic == null){
            System.out.println("Topic not found!!!" );
            return false;
        }
        SubscriptionKey subscriptionKey = new SubscriptionKey(topicName,subscriber.getId());
        Subscription subscription = subscriptions.get(subscriptionKey);
        if(subscription == null){
            System.out.println("Subscription not found!!!" );
            return false;
        }
        subscription.stop();
        subscriptions.remove(subscriptionKey);
        return true;
    }

    public void shutdown(){
        System.out.println("Shutting down broker...");
        try{
            subscriptions.values().forEach(Subscription::stop);
            subscriberExecutor.shutdown();
            if(!subscriberExecutor.awaitTermination(5, TimeUnit.SECONDS)) subscriberExecutor.shutdownNow();
        }catch (Exception ex){
            ex.printStackTrace();
        }
    }
}
