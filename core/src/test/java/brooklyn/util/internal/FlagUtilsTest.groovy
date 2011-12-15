package brooklyn.util.internal;

import static org.testng.Assert.*

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testng.annotations.Test

import brooklyn.util.flags.FlagUtils
import brooklyn.util.flags.SetFromFlag

public class FlagUtilsTest {

	public static final Logger log = LoggerFactory.getLogger(FlagUtilsTest.class);
	
	@Test
	public void testGetAllFields() {
		log.info "types {}", FlagUtils.getAllAssignableTypes(Baz)
		assertEquals FlagUtils.getAllAssignableTypes(Baz), [ Baz, Foo, Bar ];
		def fs = FlagUtils.getAllFields(Baz);
		fs.each { log.info "field {}    {}", it.name, it }
		List fsn = fs.collect { it.name }
		assertTrue fsn.indexOf("A") >= 0
		assertTrue fsn.indexOf("w") > fsn.indexOf("A") 
		assertTrue fsn.indexOf("x") > fsn.indexOf("A") 
		assertTrue fsn.indexOf("yNotY") > fsn.indexOf("A") 
		assertTrue fsn.indexOf("Z") > fsn.indexOf("yNotY") 
	}	
	
    @Test
    public void testSetFieldsFromFlags() {
        Foo f = []
		Map m = [w:3, x:1, y:7, z:9]
        def unused = FlagUtils.setFieldsFromFlags(m, f);
		assertEquals(f.w, 3)
        assertEquals(f.x, 1)
        assertEquals(f.yNotY, 7)
        assertEquals(unused, [z:9])
		Map m2 = FlagUtils.getFieldsWithValues(f)
		m -= unused
		assertEquals m2, m
    }
    
    @Test
    public void testNonImmutableField() {
        Foo f = []
        FlagUtils.setFieldsFromFlags(f, w:8);
        assertEquals f.w, 8
        FlagUtils.setFieldsFromFlags(f, w:9);
        assertEquals f.w, 9
    }

    @Test
    public void testImmutableIntField() {
        Foo f = []
        FlagUtils.setFieldsFromFlags(f, x:8);
        assertEquals f.x, 8
        boolean succeededWhenShouldntHave = false 
        try {
            FlagUtils.setFieldsFromFlags(f, x:9);
            succeededWhenShouldntHave = true
        } catch (IllegalStateException e) {
            //expected
        }
        assertFalse succeededWhenShouldntHave
        assertEquals f.x, 8
    }

    @Test
    public void testImmutableObjectField() {
        WithImmutableNonNullableObject o = []
        FlagUtils.setFieldsFromFlags(o, a:"a", b:"b");
        assertEquals o.a, "a"
        assertEquals o.b, "b"
        
        FlagUtils.setFieldsFromFlags(o, a:"a2");
        assertEquals o.a, "a2"
        
        boolean succeededWhenShouldntHave = false
        try {
            FlagUtils.setFieldsFromFlags(o, b:"b2");
            succeededWhenShouldntHave = true
        } catch (IllegalStateException e) {
            //expected
        }
        assertFalse succeededWhenShouldntHave
        assertEquals o.b, "b"
    }

    @Test
    public void testNonNullable() {
        WithImmutableNonNullableObject o = []
        //allowed
        FlagUtils.setFieldsFromFlags(o, a:null);
        assertEquals o.a, null
        assertEquals o.b, null
        //not allowed
        boolean succeededWhenShouldntHave = false
        try {
            FlagUtils.setFieldsFromFlags(o, b:null);
            succeededWhenShouldntHave = true
        } catch (IllegalStateException e) {
            //expected
        }
        assertFalse succeededWhenShouldntHave
        assertEquals o.b, null
    }

}

class Foo {
	@SetFromFlag
	int w;
	
	@SetFromFlag(immutable=true)
	private int x;
	
	@SetFromFlag("y")
	public int yNotY;
}

interface Bar {
	static String Z;
}

class Baz extends Foo implements Bar {
	private static int A;
}

class WithImmutableNonNullableObject {
    @SetFromFlag
    Object a;
    @SetFromFlag(immutable=true, nullable=false)
    Object b;
}
