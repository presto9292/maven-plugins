package com.github.goldin.plugins.common

import com.github.goldin.gcommons.GCommons
import com.github.goldin.gcommons.beans.*
import com.github.goldin.gcommons.util.GroovyConfig
import com.google.common.io.ByteStreams
import groovy.text.SimpleTemplateEngine
import groovy.text.Template
import groovyx.gpars.GParsPool
import org.apache.maven.Maven
import org.apache.maven.execution.MavenSession
import org.apache.maven.monitor.logging.DefaultLog
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.logging.Logger
import org.codehaus.plexus.logging.console.ConsoleLogger
import org.gcontracts.annotations.Ensures
import org.gcontracts.annotations.Requires


/**
 * Various Mojo helper methods
 */
@SuppressWarnings([ 'MethodCount', 'AbcMetric' ])
final class GMojoUtils
{
    /**
     * <groupId> prefix for Ivy <dependencies>.
     */
    static final String IVY_PREFIX = 'ivy.'


    private GMojoUtils (){}


    /**
     * Retrieves current {@link Log} instance
     * @return current {@link Log} instance
     */
    static Log getLog () { ThreadLocals.get( Log ) }


    /**
     * Updates Groovy MOP with additional methods
     */
     static mopInit ()
     {
         fileBean() // Triggers GCommons MOP replacements

         /**
          * Trims multi-lines String: each line in the String specified is trim()-ed
          */
         String.metaClass.trimMultiline = { ->
             (( String ) delegate ).readLines()*.trim().join( constantsBean().CRLF )
         }


         /**
          * Deletes empty lines from the String
          */
         String.metaClass.deleteEmptyLines = { ->
             (( String ) delegate ).readLines().findAll{ it.trim() }.join( constantsBean().CRLF )
         }


         /**
          * Replaces {..} expressions, not preceded by $, by ${..} to work around
          * "Disabling/escaping POM interpolation for specific variable" - http://goo.gl/NyEq
          *
          * We're putting {..} in POMs where we would like to have un-interpolated ${..}
          */
         String.metaClass.addDollar = { ->
             // Adding a '$' before {..} where there was no '$' previously
             delegate.replaceAll( /(?<!\$)(?=\{.+?\})/, '\\$' )
         }
     }


    static OutputStream nullOutputStream () { ByteStreams.nullOutputStream() }
    static PrintStream  nullPrintStream  () { new PrintStream( nullOutputStream()) }


    /**
    * Retrieves {@link SimpleTemplateEngine} for the resource specified
    */
    static Template getTemplate ( String templatePath, ClassLoader loader = GMojoUtils.classLoader )
    {
        URL    templateURL = GMojoUtils.getResource( templatePath )
        assert templateURL, "[${ templatePath }] could not be loaded from the classpath"

        verifyBean().notNull( new SimpleTemplateEngine( loader ).createTemplate( templateURL ))
    }


   /**
    * {@code GMojoUtils.getTemplate().make().toString()} wrapper
    */
    static String makeTemplate( String  templatePath,
                                Map     binding,
                                String  endOfLine        = null,
                                boolean deleteEmptyLines = false )
    {
        def content = getTemplate( templatePath ).make( binding ).toString()

        if ( endOfLine        ) { content = content.replaceAll( /\r?\n/, (( 'windows' == endOfLine ) ? '\r\n' : '\n' )) }
        if ( deleteEmptyLines ) { content = content.deleteEmptyLines() }

        verifyBean().notNullOrEmpty( content )
    }


    static Properties properties( String path, ClassLoader cl = GMojoUtils.classLoader )
    {
        assert path && cl

        InputStream is = cl.getResourceAsStream( path )
        assert      is, "Failed to load resource [$path] using ClassLoader [$cl]"

        Properties props = new Properties()
        props.load( is )
        is.close()

        props
    }


    /**
     * Retrieves Maven version as appears in "pom.properties" inside Maven jar.
     *
     * @return Maven version
     */
    static String mavenVersion()
    {
        verifyBean().notNullOrEmpty(
            properties( 'META-INF/maven/org.apache.maven/maven-core/pom.properties', Maven.classLoader ).
            getProperty( 'version', 'Unknown' ).trim())
    }


    /**
     * Determines if execution continues.
     *
     * @param s {@code <runIf>} string
     * @return true if 'runIf' is not defined or evaluates to true,
     *         false otherwise
     */
    static boolean runIf( String s )
    {
        ( 'false' == s ) ? false :
        ( 'true'  == s ) ? true  :
        ( s            ) ? Boolean.valueOf( eval( s, String )) :
                           true
    }


    /**
     * Executes the command specified and returns the result.
     */
    @Requires({ command && directory && execOption })
    static String exec ( String  command,
                         File       directory       = ThreadLocals.get( MavenProject ).basedir,
                         boolean    failOnError     = true,
                         boolean    failIfEmpty     = true,
                         ExecOption execOption      = ExecOption.Runtime,
                         int        minimalListSize = -1 )
    {
        assert command && directory

        if ( log.debugEnabled ) { log.debug( "Running [$command] in [$directory.canonicalPath]" )}

        String result = generalBean().executeWithResult( command, execOption, failOnError, -1, directory )

        if ( log.debugEnabled ) { log.debug( "Running [$command] in [$directory.canonicalPath] - result is [$result]" )}

        if ( minimalListSize > 0 )
        {
            List lines = result.readLines()
            assert lines.size() >= minimalListSize, \
                   "Received not enough data when running [$command] in [$directory.canonicalPath] - " +
                   "expected list of size [$minimalListSize] at least, received [$result]$lines of size [${ lines.size() }]"
        }

        assert ( result || ( ! failIfEmpty )), \
               "Failed to run [$command] in [$directory.canonicalPath] - result is empty [$result]"
        result
    }


    /**
     * Evaluates Groovy expression provided and casts it to the class specified.
     *
     * @param expression   Groovy expression to evaluate, if null or empty - null is returned
     * @param resultType   result's type,
     *                     if <code>null</code> - no verification is made for result's type and <code>null</code>
     *                     value is allowed to be returned from eval()-ing the expression
     * @param groovyConfig {@link GroovyConfig} object to use, allowed to be <code>null</code>
     * @param verbose      Whether Groovy evaluation should be verbose
     *
     * @param <T>        result's type
     * @return           expression evaluated and casted to the type specified
     *                   (after verifying compliance with {@link Class#isInstance(Object)}
     */
    @Requires({ expression != null })
    static <T> T eval ( String       expression,
                        Class<T>     resultType = null,
                        GroovyConfig config     = new GroovyConfig(),
                        Object ...   bindingObjects )
    {
        MavenProject project    = ThreadLocals.get( MavenProject )
        MavenSession session    = ThreadLocals.get( MavenSession )
        Map          bindingMap = [ project      : project,
                                    session      : session,
                                    mavenVersion : mavenVersion(),
                                    startTime    : session.startTime,
                                    ant          : new AntBuilder(),
                                    *:( project.properties + session.userProperties + session.systemProperties )]
        groovyBean().eval( expression,
                           resultType,
                           groovyBean().binding( bindingMap, bindingObjects ),
                           config )
    }


    /**
     * Converts an ['a', 'b', 'c'] collection to:
     *  * [a]
     *  * [b]
     *  * [c]
     *
     * @param c Collection to convert
     * @return String to use for log messages
     */
    static String stars ( Collection c ) { "* [${ c.join( "]${ constantsBean().CRLF }* [") }]" }


    /**
     * Initializes {@link ThreadLocals} storage for testing environment
     */
    static void initTestThreadLocals()
    {
        ThreadLocals.set( new MavenProject(),
                          new MavenSession( null, null, null, null, null, null, null, new Properties(), new Properties(), new Date()),
                          new DefaultLog( new ConsoleLogger( Logger.LEVEL_DEBUG, 'TestLog' )))
        mopInit()
    }


    /**
     * Retrieves maximal length of map's key.
     */
    @Requires({ map })
    @Ensures ({ result > 0 })
    static int maxKeyLength ( Map<?,?> map ) { map.keySet()*.toString()*.size().max() }


    /**
     * Sets property specified to maven project and session provided.
     *
     * @param name       name of the property to set
     * @param value      value of the property to set
     * @param logMessage log message to use when property is set, instead of the default one
     * @param verbose    whether property value set is logged or hidden
     * @param padName    number of characters to pad the property name
     */
    @Requires({ name && ( value != null ) && ( logMessage != null ) && ( padName >= 0 ) })
    static void setProperty( String name, String value, String logMessage = '', boolean verbose = true, int padName = 0 )
    {
        MavenProject project = ThreadLocals.get( MavenProject )
        MavenSession session = ThreadLocals.get( MavenSession )

        [ project.properties, session.systemProperties, session.userProperties ]*.setProperty( name, value )

        log.info( logMessage ?: '>> Maven property ' +
                                "\${$name}".padRight( padName + 3 ) +
                                ' is set' + ( verbose ? " to \"$value\"" : '' ))
    }


    /**
     * Splits a delimiter-separated String.
     *
     * @param s         String to split
     * @param delimiter delimiter (not regex!) to split the String with
     * @return result of {@code s.split( delim )*.trim().grep()}
     */
    @Requires({ delimiter })
    @Ensures ({ result != null })
    static List<String> split( String s, String delimiter = ',' ) { ( s ?: '' ).tokenize( delimiter )*.trim().grep() }


    /**
     * Add a '$' character to {..} expressions.
     *
     * @param value value containing {..} expressions.
     * @param addDollar if "false" or Groovy Truth false - no changes are made to the value,
     *                  if "true" - all {..} expressions are converted to ${..}
     *                  if list of comma-separated tokens - only {token} expressions are updated
     * @return value modified according to 'addDollar'
     */
    static String addDollar( String value, String addDollar )
    {
        String result = value

        if ( value && addDollar && ( 'false' != addDollar ))
        {
            String pattern = ( 'true' == addDollar ) ? '.+?' : split( addDollar ).collect{ String token -> "\\Q$token\\E" }.join( '|' )
            result         = value.replaceAll( ~/(?<!\$)(?=\{($pattern)\})/, '\\$' )
        }

        result
    }


    /**
     * Converts path specified to URL.
     *
     * @param s path of disk file or jar-located resource.
     * @return path's URL
     */
    @SuppressWarnings([ 'GroovyStaticMethodNamingConvention', 'GrDeprecatedAPIUsage' ])
    @Requires({ s })
    @Ensures ({ result })
    static URL url( String s )
    {
        s.trim().with { ( startsWith( 'jar:' ) || startsWith( 'file:' )) ? new URL( s ) : new File( s ).toURL() }
    }


    /**
     * Convert path to its canonical form.
     *
     * @param s path to convert
     * @return path in canonical form
     */
    static String canonicalPath ( String s )
    {
        ( s && ( ! netBean().isNet( s ))) ? new File( s ).canonicalPath.replace( '\\', '/' ) : s
    }


    /**
     * Throws a {@link MojoExecutionException} or logs a warning message according to {@code fail}.
     *
     * @param fail     whether execution should throw an exception
     * @param message  error message to throw or log
     * @param error    execution error, optional
     */
    @Requires({ message })
    static void failOrWarn( boolean fail, String message, Throwable error = null )
    {
        if ( fail )
        {
            if ( error ) { throw new MojoExecutionException( message, error )}
            else         { throw new MojoExecutionException( message )}
        }
        else
        {
            if ( error ) { log.warn( message, error )}
            else         { log.warn( message )}
        }
    }


    /**
     * Iterates over collection specified serially or in parallel.
     *
     * @param parallel whether iteration should be performed in parallel
     * @param c        collection to iterate over
     * @param action   action to perform on each iteration
     */
    @Requires({ ( c != null ) && action })
    static void each ( boolean parallel, Collection<?> c, Closure action )
    {
        if ( parallel ) { GParsPool.withPool { c.eachParallel( action ) }}
        else            { c.each( action )}
    }


    /**
     * Reads lines of the {@code String} specified, trimming and grepping them.
     * @param s String to read its lines
     * @return lines read, trimmed and grepped
     */
    @Requires({ s != null      })
    @Ensures ({ result != null })
    static List<String> readLines( String s ){ s.readLines()*.trim().grep() }


    @Requires({ file && ( content != null ) && encoding })
    @Ensures ({ result == file })
    static File write ( File file, String content, String encoding = 'UTF-8' )
    {
        fileBean().delete( file )
        fileBean().mkdirs( file.parentFile )
        file.write( content, encoding )
        assert ( file.file && ( file.size() >= content.size()))
        file
    }


    static ConstantsBean constantsBean (){ GCommons.constants ()}
    static GeneralBean   generalBean   (){ GCommons.general ()}
    static FileBean      fileBean      (){ GCommons.file ()}
    static NetBean       netBean       (){ GCommons.net ()}
    static VerifyBean    verifyBean    (){ GCommons.verify ()}
    static GroovyBean    groovyBean    (){ GCommons.groovy ()}
}