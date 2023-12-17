package org.example;

import java.util.function.Consumer;

public class Main {
    public static void greet() {
        System.out.println("Hello world!");
    }

    public void greetWithInstance() {
        System.out.println(INSTANCE_GREETING);
    }

    private static final String INSTANCE_GREETING = "Hello instance!";

    public static void main(String[] args) throws NoSuchMethodException {
        LambdaMetafactoryWrapper wrapper = new LambdaMetafactoryWrapper();
        Runnable greet = wrapper.wrap(Main.class.getMethod("greet"), Runnable.class);
        for (int i = 0; i < 5; i++) {
            greet.run();
        }
        System.out.println("Done testing wrap");
        Consumer<Main> greetWithInstance = wrapper.wrap(Main.class.getMethod("greetWithInstance"), Consumer.class);
        Main instance = new Main();
        for (int i = 0; i < 5; i++) {
            greetWithInstance.accept(instance);
        }
    }
}