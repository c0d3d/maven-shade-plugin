package org.apache.maven.plugins.shade;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

import junit.framework.TestCase;

import org.apache.commons.io.IOUtils;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.apache.maven.plugins.shade.resource.ComponentsXmlResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * @author Jason van Zyl
 * @author Mauro Talevi
 */
public class DefaultShaderTest
    extends TestCase
{
    private static final String[] EXCLUDES =
        new String[] { "org/codehaus/plexus/util/xml/Xpp3Dom", "org/codehaus/plexus/util/xml/pull.*" };

    public void testShaderWithDefaultShadedPattern()
        throws Exception
    {
        shaderWithPattern( null, new File( "target/foo-default.jar" ), EXCLUDES );
    }

    public void testShaderWithStaticInitializedClass()
        throws Exception
    {
        Shader s = newShader();

        Set<File> set = new LinkedHashSet<File>();

        set.add( new File( "src/test/jars/test-artifact-1.0-SNAPSHOT.jar" ) );

        List<Relocator> relocators = new ArrayList<Relocator>();

        relocators.add( new SimpleRelocator( "org.apache.maven.plugins.shade", null, null, null ) );

        List<ResourceTransformer> resourceTransformers = new ArrayList<ResourceTransformer>();

        List<Filter> filters = new ArrayList<Filter>();

        File file = new File( "target/testShaderWithStaticInitializedClass.jar" );

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setUberJar( file );
        shadeRequest.setFilters( filters );
        shadeRequest.setRelocators( relocators );
        shadeRequest.setResourceTransformers( resourceTransformers );
        shadeRequest.setListShadedInJar( true );

        s.shade( shadeRequest );

        URLClassLoader cl = new URLClassLoader( new URL[] { file.toURI().toURL() } );
        Class<?> c = cl.loadClass( "hidden.org.apache.maven.plugins.shade.Lib" );
        Object o = c.newInstance();
        assertEquals( "foo.bar/baz", c.getDeclaredField( "CONSTANT" ).get( o ) );
        testNumberOfShadedDeps( 1, file );
    }

    public void testShaderWithCustomShadedPattern()
        throws Exception
    {
        shaderWithPattern( "org/shaded/plexus/util", new File( "target/foo-custom.jar" ), EXCLUDES );
    }

    public void testShaderWithoutExcludesShouldRemoveReferencesOfOriginalPattern()
        throws Exception
    {
        // FIXME: shaded jar should not include references to org/codehaus/* (empty dirs) or org.codehaus.* META-INF
        // files.
        shaderWithPattern( "org/shaded/plexus/util", new File( "target/foo-custom-without-excludes.jar" ),
                           new String[] {} );
    }

    public void testShaderWithRelocatedClassname()
        throws Exception
    {
        DefaultShader s = newShader();

        Set<File> set = new LinkedHashSet<File>();

        set.add( new File( "src/test/jars/test-project-1.0-SNAPSHOT.jar" ) );

        set.add( new File( "src/test/jars/plexus-utils-1.4.1.jar" ) );

        List<Relocator> relocators = new ArrayList<Relocator>();

        relocators.add( new SimpleRelocator( "org/codehaus/plexus/util/", "_plexus/util/__", null,
                                             Arrays.<String>asList() ) );

        List<ResourceTransformer> resourceTransformers = new ArrayList<ResourceTransformer>();

        resourceTransformers.add( new ComponentsXmlResourceTransformer() );

        List<Filter> filters = new ArrayList<Filter>();

        File file = new File( "target/foo-relocate-class.jar" );

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setUberJar( file );
        shadeRequest.setFilters( filters );
        shadeRequest.setRelocators( relocators );
        shadeRequest.setResourceTransformers( resourceTransformers );
        shadeRequest.setListShadedInJar( true );
        s.shade( shadeRequest );

        URLClassLoader cl = new URLClassLoader( new URL[] { file.toURI().toURL() } );
        Class<?> c = cl.loadClass( "_plexus.util.__StringUtils" );
        // first, ensure it works:
        Object o = c.newInstance();
        assertEquals( "", c.getMethod( "clean", String.class ).invoke( o, (String) null ) );

        // now, check that its source file was rewritten:
        final String[] source = { null };
        final ClassReader classReader = new ClassReader( cl.getResourceAsStream( "_plexus/util/__StringUtils.class" ) );
        classReader.accept( new ClassVisitor( Opcodes.ASM4 )
        {
            @Override
            public void visitSource( String arg0, String arg1 )
            {
                super.visitSource( arg0, arg1 );
                source[0] = arg0;
            }
        }, ClassReader.SKIP_CODE );
        assertEquals( "__StringUtils.java", source[0] );
        testNumberOfShadedDeps( 2, file );

        // Now we re-use the uber jar we just made so we can test nested shading
        // NOTE: there should be 4 list entrys 3 for the jar we just made
        // shaded stuff + it's name, and 1 for the new jar we are adding.
        set = new LinkedHashSet<File>();
        set.add( new File( "src/test/jars/test-artifact-1.0-SNAPSHOT.jar" ) );
        set.add( file );
        File newUber = new File( "target/foo-relocate-class-nested.jar" );

        shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setUberJar( newUber );
        shadeRequest.setFilters( filters );
        shadeRequest.setRelocators( relocators );
        shadeRequest.setResourceTransformers( resourceTransformers );
        shadeRequest.setListShadedInJar( true );

        s = newShader();
        s.shade( shadeRequest );
        testNumberOfShadedDeps( 4, newUber );

        // Now we test that we aren't doubling any entries, if we include the same jar twice it should
        // only be present in the list once
        set = new LinkedHashSet<File>();
        set.add( newUber );
        newUber = new File( "target/foo-relocate-class-nested-rep.jar" );

        shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setUberJar( newUber );
        shadeRequest.setFilters( filters );
        shadeRequest.setRelocators( relocators );
        shadeRequest.setResourceTransformers( resourceTransformers );
        shadeRequest.setListShadedInJar( true );

        s = newShader();
        s.shade( shadeRequest );
        // only an increase of one due to previous jar added
        testNumberOfShadedDeps( 5, newUber );

    }

    public void testShadeWithNoInclusionMetaData()
        throws Exception
    {
        DefaultShader s = newShader();

        Set<File> set = new LinkedHashSet<File>();

        set.add( new File( "src/test/jars/test-project-1.0-SNAPSHOT.jar" ) );

        set.add( new File( "src/test/jars/plexus-utils-1.4.1.jar" ) );

        List<Relocator> relocators = new ArrayList<Relocator>();

        relocators.add( new SimpleRelocator( "org/codehaus/plexus/util/", "_plexus/util/__", null,
                                             Arrays.<String>asList() ) );

        List<ResourceTransformer> resourceTransformers = new ArrayList<ResourceTransformer>();

        resourceTransformers.add( new ComponentsXmlResourceTransformer() );

        List<Filter> filters = new ArrayList<Filter>();

        File file = new File( "target/foo-relocate-class.jar" );

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setUberJar( file );
        shadeRequest.setFilters( filters );
        shadeRequest.setRelocators( relocators );
        shadeRequest.setResourceTransformers( resourceTransformers );
        // shadeRequest.setListShadedInJar( false ); <- should be default
        s.shade( shadeRequest );
        
        testNumberOfShadedDeps( 0, file );

    }

    private void testNumberOfShadedDeps( int i, File file )
        throws Exception
    {
        JarInputStream jis = new JarInputStream( new FileInputStream( file ) );
        try
        {
            JarEntry cur = jis.getNextJarEntry();
            while ( cur != null )
            {
                if ( cur.getName().equals( DefaultShader.SHADED_DEPS_PATH ) )
                {
                    assertEquals( i, readNumLines( jis ) );
                    return;
                }
                cur = jis.getNextJarEntry();
            }
        }
        finally
        {
            jis.close();
        }
        // Here means no meta-data file so we should have expected 0
        assertEquals( i, 0 );

    }

    private int readNumLines( JarInputStream jis )
        throws IOException
    {

        return IOUtils.toString( jis ).split( Pattern.quote( IOUtils.LINE_SEPARATOR ) ).length;
    }

    private void shaderWithPattern( String shadedPattern, File jar, String[] excludes )
        throws Exception
    {
        DefaultShader s = newShader();

        Set<File> set = new LinkedHashSet<File>();

        set.add( new File( "src/test/jars/test-project-1.0-SNAPSHOT.jar" ) );

        set.add( new File( "src/test/jars/plexus-utils-1.4.1.jar" ) );

        List<Relocator> relocators = new ArrayList<Relocator>();

        relocators.add( new SimpleRelocator( "org/codehaus/plexus/util", shadedPattern, null,
                                             Arrays.asList( excludes ) ) );

        List<ResourceTransformer> resourceTransformers = new ArrayList<ResourceTransformer>();

        resourceTransformers.add( new ComponentsXmlResourceTransformer() );

        List<Filter> filters = new ArrayList<Filter>();

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars( set );
        shadeRequest.setUberJar( jar );
        shadeRequest.setFilters( filters );
        shadeRequest.setRelocators( relocators );
        shadeRequest.setResourceTransformers( resourceTransformers );
        shadeRequest.setListShadedInJar( true );

        s.shade( shadeRequest );

        testNumberOfShadedDeps( 2, jar );
    }

    private static DefaultShader newShader()
    {
        DefaultShader s = new DefaultShader();

        s.enableLogging( new ConsoleLogger( Logger.LEVEL_INFO, "TEST" ) );

        return s;
    }

}
