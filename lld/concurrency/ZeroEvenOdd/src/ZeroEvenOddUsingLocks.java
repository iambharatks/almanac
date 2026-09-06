import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

public class ZeroEvenOddUsingLocks {
    private final int n;
    private int state;
    private final Object lock = new Object();
    public ZeroEvenOddUsingLocks(int n) {
        this.n = n;
        this.state = 0;
    }

    public void zero(Consumer<Integer> printNumber) throws InterruptedException {
        for(int i = 1 ; i <= n ; i++){
            synchronized (lock){
                while(state != 0){
                    lock.wait();
                }
                printNumber.accept(0);
                state = (i%2)+1;
                lock.notifyAll();
            }
        }
    }

    public void odd(Consumer<Integer> printNumber) throws InterruptedException {
        for(int i = 1 ; i <= n ; i+=2){
            synchronized (lock){
                while(state != 2){
                    lock.wait();
                }
                printNumber.accept(i);
                state = 0;
                lock.notifyAll();
            }
        }
    }

    public void even(Consumer<Integer> printNumber) throws InterruptedException {
        for(int i = 2 ; i <= n ; i+=2){
            synchronized (lock){
                while(state != 1){
                    lock.wait();
                }
                printNumber.accept(i);
                state = 0;
                lock.notifyAll();
            }
        }
    }
}
