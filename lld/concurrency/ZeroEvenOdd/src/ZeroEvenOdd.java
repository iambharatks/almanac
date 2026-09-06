import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

public class ZeroEvenOdd {
    private final int n;
    private final Semaphore zeroSemaphore;
    private final Semaphore evenSemaphore;
    private final Semaphore oddSemaphore;

    public ZeroEvenOdd(int n) {
        this.n = n;
        zeroSemaphore = new Semaphore(1);
        evenSemaphore = new Semaphore(0);
        oddSemaphore = new Semaphore(0);
    }

    public void zero(Consumer<Integer> printNumber) throws InterruptedException {
        for(int i = 1 ; i <= n ; i++){
            zeroSemaphore.acquire();
//            System.out.print(0);
            printNumber.accept(0);
            if(i%2 == 0){
                evenSemaphore.release();
            }else{
                oddSemaphore.release();
            }
        }
    }

    public void odd(Consumer<Integer> printNumber) throws InterruptedException {
        for(int i = 1 ; i <= n ; i+=2){
            oddSemaphore.acquire();
//            System.out.print(i);
            printNumber.accept(i);
            zeroSemaphore.release();
        }
    }

    public void even(Consumer<Integer> printNumber) throws InterruptedException {
        for(int i = 2 ; i <= n ; i+=2){
            evenSemaphore.acquire();
//            System.out.print(i);
            printNumber.accept(i);
            zeroSemaphore.release();
        }
    }
}
