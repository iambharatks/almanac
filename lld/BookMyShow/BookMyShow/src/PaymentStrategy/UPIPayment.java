package PaymentStrategy;

import Entities.Payment;
import Entities.PaymentResult;

import java.util.UUID;

import static java.lang.Thread.sleep;

public class UPIPayment implements PaymentStrategy{
    @Override
    public Payment pay(double amount, String userId) {
        try{
            sleep(200);
        }catch(InterruptedException e){
            e.printStackTrace();
        }
        return new Payment(userId,amount,UUID.randomUUID().toString(), PaymentResult.SUCCESS);
    }

    @Override
    public void refund(String txnId) {
        System.out.println("Payment failed "+ txnId);
    }
}
