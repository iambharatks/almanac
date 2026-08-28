package PaymentStrategy;

import Entities.Payment;

public interface PaymentStrategy {
    public Payment pay(double amount, String userId);
    public void refund(String txnId);
}
