package demo.app;

import demo.core.Greeter;

public final class Main {
    public static void main(String[] arguments) {
        System.out.println(new Greeter().greet("Maven"));
    }
}
