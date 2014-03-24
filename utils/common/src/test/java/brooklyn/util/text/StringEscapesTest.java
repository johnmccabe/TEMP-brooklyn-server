package brooklyn.util.text;

import org.testng.Assert;
import org.testng.annotations.Test;

import brooklyn.util.collections.MutableList;
import brooklyn.util.text.StringEscapes.BashStringEscapes;
import brooklyn.util.text.StringEscapes.JavaStringEscapes;

public class StringEscapesTest {

    @Test
    public void testEscapeSql() {
        Assert.assertEquals(StringEscapes.escapeSql("I've never been to Brooklyn"), "I''ve never been to Brooklyn");
    }

    
	@Test
	public void testBashEscaping() {
		Assert.assertEquals(
	        BashStringEscapes.doubleQuoteLiteralsForBash("-Dname=Bob Johnson", "-Dnet.worth=$100"),
			"\"-Dname=Bob Johnson\" \"-Dnet.worth=\\$100\"");
	}

	@Test
	public void testBashEscapable() {
		Assert.assertTrue(BashStringEscapes.isValidForDoubleQuotingInBash("Bob Johnson"));
		Assert.assertFalse(BashStringEscapes.isValidForDoubleQuotingInBash("\""));
		Assert.assertTrue(BashStringEscapes.isValidForDoubleQuotingInBash("\\\""));
	}	
    
    @Test
    public void testBashEscapableAmpersand() {
        Assert.assertTrue(BashStringEscapes.isValidForDoubleQuotingInBash("\\&"));
        Assert.assertFalse(BashStringEscapes.isValidForDoubleQuotingInBash("Marks & Spencer"));
        Assert.assertTrue(BashStringEscapes.isValidForDoubleQuotingInBash("Marks \\& Spencer"));
        Assert.assertFalse(BashStringEscapes.isValidForDoubleQuotingInBash("Marks \\\\& Spencer"));
    }

    @Test
    public void testJavaUnwrap() {
        Assert.assertEquals(JavaStringEscapes.unwrapJavaString("\"Hello World\""), "Hello World");
        Assert.assertEquals(JavaStringEscapes.unwrapJavaString("\"Hello \\\"Bob\\\"\""), "Hello \"Bob\"");
        try {
            JavaStringEscapes.unwrapJavaString("Hello World");
            Assert.fail("Should have thrown");
        } catch (Exception e) { /* expected */ }
        try {
            // missing final quote
            JavaStringEscapes.unwrapJavaString("\"Hello \\\"Bob\\\"");
            Assert.fail("Should have thrown");
        } catch (Exception e) { /* expected */ }
        
        Assert.assertEquals(JavaStringEscapes.unwrapJavaStringIfWrapped("\"Hello World\""), "Hello World");
        Assert.assertEquals(JavaStringEscapes.unwrapJavaStringIfWrapped("\"Hello \\\"Bob\\\"\""), "Hello \"Bob\"");
        Assert.assertEquals(JavaStringEscapes.unwrapJavaStringIfWrapped("Hello World"), "Hello World");
        try {
            // missing final quote
            JavaStringEscapes.unwrapJavaStringIfWrapped("\"Hello \\\"Bob\\\"");
            Assert.fail("Should have thrown");
        } catch (Exception e) { /* expected */ }
    }
    
    @Test
    public void testJavaEscape() {
        Assert.assertEquals(JavaStringEscapes.wrapJavaString("Hello \"World\""), "\"Hello \\\"World\\\"\"");
    }
    
    @Test
    public void testJavaLists() {
        Assert.assertEquals(MutableList.of("hello", "world"),
            JavaStringEscapes.unwrapQuotedJavaStringList("\"hello\", \"world\"", ","));
        try {
            JavaStringEscapes.unwrapQuotedJavaStringList("\"hello\", world", ",");
            Assert.fail("Should have thrown");
        } catch (Exception e) { /* expected */ }
        
        Assert.assertEquals(MutableList.of("hello", "world"),
            JavaStringEscapes.unwrapJsonishListIfPossible("\"hello\", \"world\""));
        Assert.assertEquals(MutableList.of("hello"),
            JavaStringEscapes.unwrapJsonishListIfPossible("hello"));
        Assert.assertEquals(MutableList.of("hello", "world"),
            JavaStringEscapes.unwrapJsonishListIfPossible("hello, world"));
        Assert.assertEquals(MutableList.of("hello", "world"),
            JavaStringEscapes.unwrapJsonishListIfPossible("\"hello\", world"));
        Assert.assertEquals(MutableList.of("hello", "world"),
            JavaStringEscapes.unwrapJsonishListIfPossible("[ \"hello\", world ]"));
        // if can't parse e.g. because contains double quote, then returns original string as single element list
        Assert.assertEquals(MutableList.of("hello\", \"world\""),
            JavaStringEscapes.unwrapJsonishListIfPossible("hello\", \"world\""));
        Assert.assertEquals(MutableList.of(),
            JavaStringEscapes.unwrapJsonishListIfPossible(" "));
        Assert.assertEquals(MutableList.of(""),
            JavaStringEscapes.unwrapJsonishListIfPossible("\"\""));
        Assert.assertEquals(MutableList.of("x"),
            JavaStringEscapes.unwrapJsonishListIfPossible(",,x,"));
        Assert.assertEquals(MutableList.of("","x",""),
            JavaStringEscapes.unwrapJsonishListIfPossible("\"\",,x,\"\""));
    }

}
