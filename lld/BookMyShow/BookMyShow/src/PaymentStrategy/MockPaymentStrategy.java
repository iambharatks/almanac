package PaymentStrategy;

import Entities.Payment;
import Entities.PaymentResult;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class MockPaymentStrategy implements  PaymentStrategy {
    private final double failureRate;
    private final long latencyMillis;
    public MockPaymentStrategy(double failureRate, long latencyMillis) {
        this.failureRate = failureRate;
        this.latencyMillis = latencyMillis;
    }
    @Override
    public Payment pay(double amount, String userId) {
        try{
            Thread.sleep(latencyMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if(ThreadLocalRandom.current().nextDouble() < failureRate){
            System.out.println("Payment failed via Mock");
            return new Payment(userId,amount,null, PaymentResult.FAILURE);
        }
        System.out.println("Payment succeeded via Mock");
        return new Payment(userId, amount, UUID.randomUUID().toString(),PaymentResult.SUCCESS);
    }

    @Override
    public void refund(String txnId) {
        System.out.println("Payment failed "+ txnId + " via Mock");
    }
}
