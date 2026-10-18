package demo.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GreeterTest {
    @Test
    public void greetsByName() {
        assertEquals("Hello, Lithe", new Greeter().greet("Lithe"));
    }

    @Test
    public void keepsEmptyNames() {
        assertEquals("Hello, ", new Greeter().greet(""));
    }
}
