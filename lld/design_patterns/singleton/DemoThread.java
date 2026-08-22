public class DemoThread {
    public static void main(String[] args) {
        System.out.println("If you see the same value, then singleton was reused (yay!)" + "\n" +
                "If you see different values, then 2 singletons were created (booo!!)" + "\n\n" +
                "RESULT:" + "\n");
        Thread threadFoo = new Thread(new Thread1());
        Thread threadBar = new Thread(new Thread2());
        threadFoo.start();
        threadBar.start();
    }

    public static class Thread1 implements Runnable{
        public void run(){
            Singleton singleton = Singleton.getInstance("FOO");
            System.out.println(singleton.value);
        }
    }
    public static class Thread2 implements Runnable{
        public void run(){
            Singleton singleton = Singleton.getInstance("BAR");
            System.out.println(singleton.value);
        }
    }
};