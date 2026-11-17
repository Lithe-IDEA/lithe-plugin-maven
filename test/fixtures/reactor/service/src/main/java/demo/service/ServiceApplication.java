package demo.service;

import demo.core.Greeter;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// A comment naming @SpringBootApplication does not count; the annotation below does.
@SpringBootApplication
public class ServiceApplication {
    public static void main(String[] arguments) {
        System.out.println(new Greeter().greet("service") + " greeting=" + System.getProperty("greeting")
                + " arguments=" + String.join(",", arguments) + " env=" + System.getenv("LITHE_FIXTURE"));
    }
}
