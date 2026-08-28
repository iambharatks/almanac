package Entities;

public record Payment(String userId, double amount, String txnId, PaymentResult paymentResult) {
}
