// plasmaParser.java 
// (C) 2005 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published in january 2005 on http://yacy.net
// with contributions 02.05.2005 by Martin Thelian
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2006-04-02 22:40:07 +0200 (So, 02 Apr 2006) $
// $LastChangedRevision: 1986 $
// $LastChangedBy: orbiter $
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.plasma;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import de.anomic.crawler.ErrorURL;
import de.anomic.htmlFilter.htmlFilterContentScraper;
import de.anomic.htmlFilter.htmlFilterImageEntry;
import de.anomic.htmlFilter.htmlFilterInputStream;
import de.anomic.htmlFilter.htmlFilterWriter;
import de.anomic.http.JakartaCommonsHttpClient;
import de.anomic.http.JakartaCommonsHttpResponse;
import de.anomic.plasma.parser.Parser;
import de.anomic.plasma.parser.ParserException;
import de.anomic.plasma.parser.ParserInfo;
import de.anomic.server.serverFileUtils;
import de.anomic.server.logging.serverLog;
import de.anomic.yacy.yacyURL;

public final class plasmaParser {
    public static final String PARSER_MODE_PROXY         = "PROXY";
    public static final String PARSER_MODE_CRAWLER       = "CRAWLER";
    public static final String PARSER_MODE_URLREDIRECTOR = "URLREDIRECTOR";
    public static final String PARSER_MODE_ICAP          = "ICAP";
    public static final String PARSER_MODE_IMAGE         = "IMAGE";
    public static final HashSet<String> PARSER_MODE = new HashSet<String>(Arrays.asList(new String[]{
            PARSER_MODE_PROXY,
            PARSER_MODE_CRAWLER,
            PARSER_MODE_ICAP,
            PARSER_MODE_URLREDIRECTOR,
            PARSER_MODE_IMAGE
    }));
    
    private static final HashMap<String, plasmaParserConfig> parserConfigList = new HashMap<String, plasmaParserConfig>();
    
    /**
     * A list containing all installed parsers and the mimeType that they support
     * @see #loadAvailableParserList()
     */
    public static final HashMap<String, ParserInfo> availableParserList = new HashMap<String, ParserInfo>();
    
    /**
     * A list of file extensions and mime types that are supported by the html-parser
     */
    public static final HashSet<String> supportedHTMLFileExt = new HashSet<String>();
    public static final HashSet<String> supportedHTMLMimeTypes = new HashSet<String>();    
    
    private static final Properties mimeTypeLookupByFileExt = new Properties();
    static {
        // loading a list of extensions from file
        BufferedInputStream bufferedIn = null;
        try {            
            mimeTypeLookupByFileExt.load(bufferedIn = new BufferedInputStream(new FileInputStream(new File("httpd.mime"))));
        } catch (IOException e) {
            System.err.println("ERROR: httpd.mime not found in settings path");
        } finally {
            if (bufferedIn != null) try{bufferedIn.close();}catch(Exception e){}
        }    
    }

    /**
     * A list of media extensions that should <b>not</b> be handled by the plasmaParser
     */
    private static final HashSet<String> mediaExtSet = new HashSet<String>();
    
    /**
     * A list of image, audio, video and application extensions
     */
    private static final HashSet<String> imageExtSet = new HashSet<String>();
    private static final HashSet<String> audioExtSet = new HashSet<String>();
    private static final HashSet<String> videoExtSet = new HashSet<String>();
    private static final HashSet<String> appsExtSet = new HashSet<String>();
    
    /**
     * This {@link FilenameFilter} is used to find all classes based on there filenames 
     * which seems to be additional content parsers.
     * Currently the filenames of all content parser classes must end with <code>Parser.class</code> 
     */
    private static final FilenameFilter parserFileNameFilter = new FilenameFilter() {
        public boolean accept(File dir, String name) {
            return name.endsWith("Parser.class");
        }
    };
    
    /**
     * This {@link FileFilter} is used to get all subpackages
     * of the parser package.
     */
    private static final FileFilter parserDirectoryFilter = new FileFilter() {
        public boolean accept(File file) {
            return file.isDirectory();
        }
    };    
    
    /**
     * Initializing the 
     * @see #initMediaExt(String)
     */
    static {
        String apps = "sit,hqx,img,dmg,exe,com,bat,sh,vbs,zip,jar";
        String audio = "mp2,mp3,ogg,aac,aif,aiff,wav";
        String video = "swf,avi,wmv,rm,mov,mpg,mpeg,ram,m4v";
        String image = "jpg,jpeg,jpe,gif,png,ico,bmp";
        initMediaExt(extString2extList(
                apps + "," +  // application container
                "tar,gz,bz2,arj,zip,rar," + // archive formats
                "ps,xls,ppt,asf," +         // text formats without support
                audio + "," +               // audio formats
                video + "," +               // video formats
                image                       // image formats
                ));
        initImageExt(extString2extList(image));  // image formats
        initAudioExt(extString2extList(audio));  // audio formats
        initVideoExt(extString2extList(video));  // video formats
        initAppsExt(extString2extList(apps));    // application formats
        
        /* ===================================================
         * loading a list of available parsers
         * =================================================== */        
        loadAvailableParserList();      
    }
    
    private serverLog theLogger = new serverLog("PARSER");
    
    public serverLog getLogger() {
        return this.theLogger;
    }
    
    public static HashMap<String, plasmaParserConfig> getParserConfigList() {
        return parserConfigList;
    }
    
    /**
     * This function is used to initialize the HTMLParsableMimeTypes List.
     * This list contains a list of mimeTypes that can be parsed in realtime by
     * the yacy html-Parser
     * @param htmlParsableMimeTypes a list of mimetypes that can be parsed by the 
     * yacy html parser
     */
    public static void initHTMLParsableMimeTypes(String htmlParsableMimeTypes) {
        LinkedList<String> mimeTypes = new LinkedList<String>();
        if ((htmlParsableMimeTypes == null) || (htmlParsableMimeTypes.length() == 0)) {
            return;
        }
        String[] realtimeParsableMimeTypeList = htmlParsableMimeTypes.split(",");        
        for (int i = 0; i < realtimeParsableMimeTypeList.length; i++) {
            mimeTypes.add(realtimeParsableMimeTypeList[i].toLowerCase().trim());
        }
        synchronized (supportedHTMLMimeTypes) {
            supportedHTMLMimeTypes.clear();
            supportedHTMLMimeTypes.addAll(mimeTypes);
        }        
    }
    
    public static List<String> extString2extList(String extString) {
        LinkedList<String> extensions = new LinkedList<String>();
        if ((extString == null) || (extString.length() == 0)) {
            return extensions;
        } else {
            String[] xs = extString.split(",");
            for (int i = 0; i < xs.length; i++) extensions.add(xs[i].toLowerCase().trim());
        }
        return extensions;
    }
    
    public static void initMediaExt(List<String> mediaExtList) {
        synchronized (mediaExtSet) {
            mediaExtSet.clear();
            mediaExtSet.addAll(mediaExtList);
        }
    }
    
    public static void initImageExt(List<String> imageExtList) {
        synchronized (imageExtSet) {
            imageExtSet.clear();
            imageExtSet.addAll(imageExtList);
        }
    }
    
    public static void initAudioExt(List<String> audioExtList) {
        synchronized (audioExtSet) {
            audioExtSet.clear();
            audioExtSet.addAll(audioExtList);
        }
    }
    
    public static void initVideoExt(List<String> videoExtList) {
        synchronized (videoExtSet) {
            videoExtSet.clear();
            videoExtSet.addAll(videoExtList);
        }
    }
    
    public static void initAppsExt(List<String> appsExtList) {
        synchronized (appsExtSet) {
            appsExtSet.clear();
            appsExtSet.addAll(appsExtList);
        }
    }
    
    public static String getMediaExtList() {
        synchronized (mediaExtSet) {
            return mediaExtSet.toString();
        }        
    }
    
    public static void initSupportedHTMLFileExt(List<String> supportedRealtimeFileExtList) {
        synchronized (supportedHTMLFileExt) {
            supportedHTMLFileExt.clear();
            supportedHTMLFileExt.addAll(supportedRealtimeFileExtList);
        }
    }
        
    public static boolean HTMLParsableMimeTypesContains(String mimeType) {
        mimeType = normalizeMimeType(mimeType);
        synchronized (supportedHTMLMimeTypes) {
            return supportedHTMLMimeTypes.contains(mimeType);
        }
    }
    
    public static boolean supportedHTMLContent(yacyURL url, String mimeType) {
        return HTMLParsableMimeTypesContains(mimeType) && supportedHTMLFileExtContains(url);
    }    
    
    public static boolean supportedHTMLFileExtContains(yacyURL url) {
        String fileExt = getFileExt(url);
        synchronized (supportedHTMLFileExt) {
            return supportedHTMLFileExt.contains(fileExt);
        }   
    }

    public static String getFileExt(yacyURL url) {
        // getting the file path
        String name = url.getPath();
        
        // tetermining last position of / in the file path
        int p = name.lastIndexOf('/');
        if (p != -1) {
            name = name.substring(p);
        }
            
        // termining last position of . in file path
        p = name.lastIndexOf('.');
        if (p < 0) return ""; 
        return name.substring(p + 1);        
    }

    public static boolean mediaExtContains(String mediaExt) {
        if (mediaExt == null) return false;
        mediaExt = mediaExt.trim().toLowerCase();
        
        synchronized (supportedHTMLFileExt) {
            if (supportedHTMLFileExt.contains(mediaExt)) return false;
        }        
        
        if (supportedFileExtContains(mediaExt)) return false;
        
        synchronized (mediaExtSet) {
			return mediaExtSet.contains(mediaExt);
		}
    }

    public static boolean imageExtContains(String imageExt) {
        if (imageExt == null) return false;
        synchronized (imageExtSet) {
            return imageExtSet.contains(imageExt.trim().toLowerCase());
        }
    }

    public static boolean audioExtContains(String audioExt) {
        if (audioExt == null) return false;
        synchronized (audioExtSet) {
            return audioExtSet.contains(audioExt.trim().toLowerCase());
        }
    }

    public static boolean videoExtContains(String videoExt) {
        if (videoExt == null) return false;
        synchronized (videoExtSet) {
            return videoExtSet.contains(videoExt.trim().toLowerCase());
        }
    }

    public static boolean appsExtContains(String appsExt) {
        if (appsExt == null) return false;
        synchronized (appsExtSet) {
            return appsExtSet.contains(appsExt.trim().toLowerCase());
        }
    }

    /**
     * some html authors use wrong encoding names, either because they don't know exactly what they
     * are doing or they produce a type. Many times, the upper/downcase scheme of the name is fuzzy
     * This method patches wrong encoding names. The correct names are taken from
     * http://www.iana.org/assignments/character-sets
     * @param encoding
     * @return patched encoding name
     */
    public static String patchCharsetEncoding(String encoding) {
        
        // return a default encoding
    	if ((encoding == null) || (encoding.length() < 3)) return "ISO-8859-1";
    	
    	// trim encoding string
    	encoding = encoding.trim();

    	// fix upper/lowercase
    	encoding = encoding.toUpperCase();
    	if (encoding.startsWith("SHIFT")) return "Shift_JIS";
    	if (encoding.startsWith("BIG")) return "Big5";
    	// all other names but such with "windows" use uppercase
    	if (encoding.startsWith("WINDOWS")) encoding = "windows" + encoding.substring(7);
    	
    	// fix wrong fill characters
    	encoding.replaceAll("_", "-");

        if (encoding.matches("GB[_-]?2312([-_]80)?")) return "GB2312";
        if (encoding.matches(".*UTF[-_]?8.*")) return "UTF-8";
        if (encoding.startsWith("US")) return "US-ASCII";
        if (encoding.startsWith("KOI")) return "KOI8-R";
    	
        // patch missing '-'
        if (encoding.startsWith("windows") && encoding.length() > 7) {
    	    char c = encoding.charAt(7);
    		if ((c >= '0') && (c <= '9')) {
    		    encoding = "windows-" + encoding.substring(7);
    		}
    	}
    	
    	if (encoding.startsWith("ISO")) {
            // patch typos
    	    if (encoding.length() > 3) {
                char c = encoding.charAt(3);
                if ((c >= '0') && (c <= '9')) {
                    encoding = "ISO-" + encoding.substring(3);
                }
    	    }
    	    if (encoding.length() > 8) {
    	        char c = encoding.charAt(8);
                if ((c >= '0') && (c <= '9')) {
                    encoding = encoding.substring(0, 8) + "-" + encoding.substring(8);           
                } 
    	    }
        }
    	
    	// patch wrong name
    	if (encoding.startsWith("ISO-8559")) {
    	    // popular typo
    	    encoding = "ISO-8859" + encoding.substring(8);
        }

    	// converting cp\d{4} -> windows-\d{4}
    	if (encoding.matches("CP([_-])?125[0-8]")) {
    		char c = encoding.charAt(2);
    		if ((c >= '0') && (c <= '9')) {
    		    encoding = "windows-" + encoding.substring(2);
    		} else {
    		    encoding = "windows" + encoding.substring(2);
    		}
    	}

    	return encoding;
    }
    
    public static String normalizeMimeType(String mimeType) {
        //if (mimeType == null) doMimeTypeAnalysis
        if (mimeType == null) mimeType = "application/octet-stream";
        mimeType = mimeType.trim().toLowerCase();
        
        int pos = mimeType.indexOf(';');
        return ((pos < 0) ? mimeType : mimeType.substring(0, pos));              
    }
    
    public static String getMimeTypeByFileExt(String fileExt) {        
        return mimeTypeLookupByFileExt.getProperty(fileExt,"application/octet-stream");
    }

    public HashMap<String, ParserInfo> getAvailableParserList() {
        return plasmaParser.availableParserList;
    }    
    
    private static void loadAvailableParserList() {
        try {
            plasmaParser.availableParserList.clear();
            
            // getting the current java classpath
            String javaClassPath = System.getProperty("java.class.path");
            
            // getting the current package name
            String plasmaParserPkgName = plasmaParser.class.getPackage().getName() + ".parser";
            serverLog.logInfo("PARSER","Searching for additional content parsers in package " + plasmaParserPkgName);
            
            // getting an uri to the parser subpackage
            String packageURI = plasmaParser.class.getResource("/"+plasmaParserPkgName.replace('.','/')).toString();
            serverLog.logFine("PARSER", "Parser directory is " + packageURI);
            
            // open the parser directory
            File parserDir = new File(new URI(packageURI));
            if ((parserDir == null) || (!parserDir.exists()) || (!parserDir.isDirectory())) return;
            
            /*
             * loop through all subdirectories and test if we can
             * find an additional parser class
             */
            File[] parserDirectories = parserDir.listFiles(parserDirectoryFilter);
            if (parserDirectories == null) return;
            
            for (int parserDirNr=0; parserDirNr< parserDirectories.length; parserDirNr++) {
                File currentDir = parserDirectories[parserDirNr];
                serverLog.logFine("PARSER", "Searching in directory " + currentDir.toString());
                
                String[] parserClasses = currentDir.list(parserFileNameFilter);
                if (parserClasses == null) continue;
                
                for (int parserNr=0; parserNr<parserClasses.length; parserNr++) {
                    serverLog.logFine("PARSER", "Testing parser class " + parserClasses[parserNr]);
                    String className = parserClasses[parserNr].substring(0,parserClasses[parserNr].indexOf(".class"));
                    String fullClassName = plasmaParserPkgName + "." + currentDir.getName() + "." + className;
                    try {
                        // trying to load the parser class by its name
                        Class<?> parserClass = Class.forName(fullClassName);
                        Object theParser0 = (Parser) parserClass.newInstance();
                        if (!(theParser0 instanceof Parser)) continue;
                        Parser theParser = (Parser) theParser0;
                        
                        // testing if all needed libx libraries are available
                        String[] neededLibx = theParser.getLibxDependences();
                        StringBuffer neededLibxBuf = new StringBuffer();
                        if (neededLibx != null) {
                            for (int libxId=0; libxId < neededLibx.length; libxId++) {
                                if (javaClassPath.indexOf(neededLibx[libxId]) == -1) {
                                    throw new Exception("Missing dependency detected: '" + neededLibx[libxId] + "'.");
                                }
                                neededLibxBuf.append(neededLibx[libxId])
                                             .append(",");
                            }
                            if (neededLibxBuf.length()>0) neededLibxBuf.deleteCharAt(neededLibxBuf.length()-1);
                        }
                        
                        // loading the list of mime-types that are supported by this parser class
                        Hashtable<String, String> supportedMimeTypes = theParser.getSupportedMimeTypes();
                        
                        // creating a parser info object
                        ParserInfo parserInfo = new ParserInfo();
                        parserInfo.parserClass = parserClass;
                        parserInfo.parserClassName = fullClassName;
                        parserInfo.libxDependencies = neededLibx;
                        parserInfo.supportedMimeTypes = supportedMimeTypes;
                        parserInfo.parserVersionNr = ((Parser)theParser).getVersion();
                        parserInfo.parserName = ((Parser) theParser).getName();
                        
                        Iterator<String> mimeTypeIterator = supportedMimeTypes.keySet().iterator();
                        while (mimeTypeIterator.hasNext()) {
                            String mimeType = (String) mimeTypeIterator.next();
                            availableParserList.put(mimeType, parserInfo);
                            serverLog.logInfo("PARSER", "Found functional parser for mimeType '" + mimeType + "'." +
                                              "\n\tName:    " + parserInfo.parserName + 
                                              "\n\tVersion: " + parserInfo.parserVersionNr + 
                                              "\n\tClass:   " + parserInfo.parserClassName +
                                              ((neededLibxBuf.length()>0)?"\n\tDependencies: " + neededLibxBuf.toString():""));
                        }
                        
                    } catch (Exception e) { /* we can ignore this for the moment */
                        serverLog.logWarning("PARSER", "Parser '" + className + "' doesn't work correctly and will be ignored.\n [" + e.getClass().getName() + "]: " + e.getMessage());
                        e.printStackTrace();
                    } catch (Error e) { /* we can ignore this for the moment */
                        serverLog.logWarning("PARSER", "Parser '" + className + "' doesn't work correctly and will be ignored.\n [" + e.getClass().getName() + "]: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
            
        } catch (Exception e) {
            serverLog.logSevere("PARSER", "Unable to determine all installed parsers. " + e.getMessage());
        }
    }
    
    public void close() {
        // clearing the parser list
        Iterator<plasmaParserConfig> configs = parserConfigList.values().iterator();
        while (configs.hasNext()) {
            plasmaParserConfig currentConfig = configs.next();
            synchronized (currentConfig.enabledParserList) {
                currentConfig.enabledParserList.clear();
            }
        }
    }    
    
    public plasmaParserDocument parseSource(yacyURL location, String mimeType, String charset, byte[] sourceArray) 
    throws InterruptedException, ParserException {
        ByteArrayInputStream byteIn = null;
        try {
            if (this.theLogger.isFine())
                this.theLogger.logFine("Parsing '" + location + "' from byte-array");
            
            // testing if the resource is not empty
            if (sourceArray == null || sourceArray.length == 0) {
                String errorMsg = "No resource content available (1).";
                this.theLogger.logInfo("Unable to parse '" + location + "'. " + errorMsg);
                throw new ParserException(errorMsg,location,ErrorURL.DENIED_NOT_PARSEABLE_NO_CONTENT);
            }              
            
            // creating an InputStream
            byteIn = new ByteArrayInputStream(sourceArray);
            
            // parsing the temp file
            return parseSource(location, mimeType, charset, sourceArray.length, byteIn);
            
        } catch (Exception e) {
            // Interrupted- and Parser-Exceptions should pass through
            if (e instanceof InterruptedException) throw (InterruptedException) e;
            if (e instanceof ParserException) throw (ParserException) e;
            
            // log unexpected error
            this.theLogger.logSevere("Unexpected exception in parseSource from byte-array: " + e.getMessage(), e);
            throw new ParserException("Unexpected exception while parsing " + location,location, e);
        } finally {
            if (byteIn != null) try { byteIn.close(); } catch (Exception ex){/* ignore this */}
        }
        
    }

    public plasmaParserDocument parseSource(yacyURL location, String theMimeType, String theDocumentCharset, File sourceFile) throws InterruptedException, ParserException {
        
        BufferedInputStream sourceStream = null;
        try {
            if (this.theLogger.isFine())
                this.theLogger.logFine("Parsing '" + location + "' from file");
            
            // testing if the resource is not empty
            if (!(sourceFile.exists() && sourceFile.canRead() && sourceFile.length() > 0)) {
                String errorMsg = sourceFile.exists() ? "Empty resource file." : "No resource content available (2).";
                this.theLogger.logInfo("Unable to parse '" + location + "'. " + errorMsg);
                throw new ParserException(errorMsg,location,ErrorURL.DENIED_NOT_PARSEABLE_NO_CONTENT);
            }        
            
            // create a new InputStream
            sourceStream = new BufferedInputStream(new FileInputStream(sourceFile));
            
            // parsing the data
            return this.parseSource(location, theMimeType, theDocumentCharset, sourceFile.length(),  sourceStream);
            
        } catch (Exception e) {
            // Interrupted- and Parser-Exceptions should pass through
            if (e instanceof InterruptedException) throw (InterruptedException) e;
            if (e instanceof ParserException) throw (ParserException) e;

            // log unexpected error
            this.theLogger.logSevere("Unexpected exception in parseSource from File: " + e.getMessage(), e);
            throw new ParserException("Unexpected exception while parsing " + location,location, e);
        } finally {
            if (sourceStream != null) try { sourceStream.close(); } catch (Exception ex){/* ignore this */}
        }
    }
    
    /**
     * To parse a resource from an {@link InputStream}
     * @param location the URL of the resource
     * @param theMimeType the resource mimetype (<code>null</code> if unknown)
     * @param theDocumentCharset the charset of the resource (<code>null</code> if unknown)
     * @param contentLength the content length of the resource (<code>-1</code> if unknown)
     * @param sourceStream an {@link InputStream} containing the resource body 
     * @return the parsed {@link plasmaParserDocument document}
     * @throws InterruptedException
     * @throws ParserException
     */
    public plasmaParserDocument parseSource(yacyURL location, String theMimeType, String theDocumentCharset, long contentLength, InputStream sourceStream) throws InterruptedException, ParserException {        
        Parser theParser = null;
        String mimeType = null;
        try {
            if (this.theLogger.isFine())
                this.theLogger.logFine("Parsing '" + location + "' from stream");            
            
            // getting the mimetype of the document
            mimeType = normalizeMimeType(theMimeType);
            
            // getting the file extension of the document
            String fileExt = getFileExt(location);
            
            // getting the charset of the document
            // TODO: do a charset detection here ....
            String documentCharset = patchCharsetEncoding(theDocumentCharset);
            
            // testing if parsing is supported for this resource
            if (!plasmaParser.supportedContent(location,mimeType)) {
                String errorMsg = "No parser available to parse mimetype '" + mimeType + "'";
                this.theLogger.logInfo("Unable to parse '" + location + "'. " + errorMsg);
                throw new ParserException(errorMsg,location,ErrorURL.DENIED_WRONG_MIMETYPE_OR_EXT);
            }
            
            if (this.theLogger.isFine())
                this.theLogger.logInfo("Parsing " + location + " with mimeType '" + mimeType + 
                                       "' and file extension '" + fileExt + "'.");                
            
            // getting the correct parser for the given mimeType
            theParser = this.getParser(mimeType);
            
            // if a parser was found we use it ...
            plasmaParserDocument doc = null;
            if (theParser != null) {
                // set the content length of the resource
                theParser.setContentLength(contentLength);
                // parse the resource
                doc = theParser.parse(location, mimeType,documentCharset,sourceStream);
            } else if (HTMLParsableMimeTypesContains(mimeType)) {
                doc = parseHtml(location, mimeType, documentCharset, sourceStream);
            } else {
                String errorMsg = "No parser available to parse mimetype '" + mimeType + "'";
                this.theLogger.logInfo("Unable to parse '" + location + "'. " + errorMsg);
                throw new ParserException(errorMsg,location,ErrorURL.DENIED_WRONG_MIMETYPE_OR_EXT);                
            }
            
            // check result
            if (doc == null) {
                String errorMsg = "Unexpected error. Parser returned null.";
                this.theLogger.logInfo("Unable to parse '" + location + "'. " + errorMsg);
                throw new ParserException(errorMsg,location);                
            }
            return doc;
            
        } catch (UnsupportedEncodingException e) {
            String errorMsg = "Unsupported charset encoding: " + e.getMessage();
            this.theLogger.logSevere("Unable to parse '" + location + "'. " + errorMsg, e);
            throw new ParserException(errorMsg,location,ErrorURL.DENIED_UNSUPPORTED_CHARSET);                	
        } catch (Exception e) {
            // Interrupted- and Parser-Exceptions should pass through
            if (e instanceof InterruptedException) throw (InterruptedException) e;
            if (e instanceof ParserException) throw (ParserException) e;
            
            // log unexpected error
            String errorMsg = "Unexpected exception. " + e.getMessage();
            this.theLogger.logSevere("Unable to parse '" + location + "'. " + errorMsg, e);
            throw new ParserException(errorMsg,location,e);            
            
        } finally {
            if (theParser != null) {
                theParser = null; // delete object
            }
        }        
    }
    
    private plasmaParserDocument parseHtml(yacyURL location, String mimeType, String documentCharset, InputStream sourceStream) throws IOException, ParserException {
        
        // make a scraper and transformer
        htmlFilterInputStream htmlFilter = new htmlFilterInputStream(sourceStream,documentCharset,location,null,false);
        String charset = htmlFilter.detectCharset();
        if (charset == null) {
            charset = documentCharset;
        } else {
        	charset = patchCharsetEncoding(charset);
        }
        
        if (!documentCharset.equalsIgnoreCase(charset)) {
            this.theLogger.logInfo("Charset transformation needed from '" + documentCharset + "' to '" + charset + "' for URL = " + location.toNormalform(true, true));
        }
        
        // parsing the content
        htmlFilterContentScraper scraper = new htmlFilterContentScraper(location);        
        htmlFilterWriter writer = new htmlFilterWriter(null,null,scraper,null,false);
        serverFileUtils.copy(htmlFilter, writer, charset);
        writer.close();
        //OutputStream hfos = new htmlFilterOutputStream(null, scraper, null, false);            
        //serverFileUtils.copy(sourceFile, hfos);
        //hfos.close();
        if (writer.binarySuspect()) {
            String errorMsg = "Binary data found in resource";
            this.theLogger.logSevere("Unable to parse '" + location + "'. " + errorMsg);
            throw new ParserException(errorMsg,location);    
        }
        return transformScraper(location, mimeType, documentCharset, scraper);
    }
    
    public plasmaParserDocument transformScraper(yacyURL location, String mimeType, String charSet, htmlFilterContentScraper scraper) {
        String[] sections = new String[scraper.getHeadlines(1).length + scraper.getHeadlines(2).length + scraper.getHeadlines(3).length + scraper.getHeadlines(4).length];
        int p = 0;
        for (int i = 1; i <= 4; i++) for (int j = 0; j < scraper.getHeadlines(i).length; j++) sections[p++] = scraper.getHeadlines(i)[j];
        plasmaParserDocument ppd =  new plasmaParserDocument(
                location,
                mimeType,
                charSet,
                scraper.getKeywords(),
                scraper.getTitle(),
                scraper.getAuthor(),
                sections,
                scraper.getDescription(),
                scraper.getText(),
                scraper.getAnchors(),
                scraper.getImages());
        //scraper.close();            
        ppd.setFavicon(scraper.getFavicon());
        return ppd;
    }
    
    /**
     * This function is used to determine the parser class that should be used for a given
     * mimetype ...
     * @param mimeType MIME-Type of the resource
     * @return the {@link Parser}-class that is supposed to parse the resource of
     * the given MIME-Type
     */
    private Parser getParser(String mimeType) {

        mimeType = normalizeMimeType(mimeType);        
        try {
            
            // determining the proper parser class name for the mimeType
            String parserClassName = null;
            ParserInfo parserInfo = null;
            synchronized (plasmaParser.availableParserList) {
    	        if (plasmaParser.availableParserList.containsKey(mimeType)) {
                    parserInfo = (ParserInfo)plasmaParser.availableParserList.get(mimeType);
    	            parserClassName = parserInfo.parserClassName;
    	        } else {
                    return null;
    	        }
			}
            
            // fetching a new parser object from pool  
			Parser theParser = makeParser(parserClassName);
            
            // checking if the created parser really supports the given mimetype 
            Hashtable<String, String> supportedMimeTypes = theParser.getSupportedMimeTypes();
            if ((supportedMimeTypes != null) && (supportedMimeTypes.containsKey(mimeType))) {
                parserInfo.incUsageCounter();
				return theParser;
            }
            
        } catch (Exception e) {
            System.err.println("ERROR: Unable to load the correct parser for type " + mimeType);
        }
        
        return null;
        
    }
    
    static Map<yacyURL, String> allReflinks(Collection<?> links) {
        // links is either a Set of Strings (with urls) or htmlFilterImageEntries
        // we find all links that are part of a reference inside a url
        HashMap<yacyURL, String> v = new HashMap<yacyURL, String>();
        Iterator<?> i = links.iterator();
        Object o;
        yacyURL url;
        String u;
        int pos;
        loop: while (i.hasNext()) try {
            o = i.next();
            if (o instanceof yacyURL) url = (yacyURL) o;
            else if (o instanceof String) url = new yacyURL((String) o, null);
            else if (o instanceof htmlFilterImageEntry) url = ((htmlFilterImageEntry) o).url();
            else {
                assert false;
                continue;
            }
            u = url.toNormalform(true, true);
            if ((pos = u.toLowerCase().indexOf("http://",7)) > 0) {
                i.remove();
                u = u.substring(pos);
                while ((pos = u.toLowerCase().indexOf("http://",7)) > 0) u = u.substring(pos);
                url = new yacyURL(u, null);
                if (!(v.containsKey(url))) v.put(url, "ref");
                continue loop;
            }
            if ((pos = u.toLowerCase().indexOf("/www.",7)) > 0) {
                i.remove();
                u = "http:/" + u.substring(pos);
                while ((pos = u.toLowerCase().indexOf("/www.",7)) > 0) u = "http:/" + u.substring(pos);
                url = new yacyURL(u, null);
                if (!(v.containsKey(url))) v.put(url, "ref");
                continue loop;
            }
        } catch (MalformedURLException e) {}
        return v;
    }
    
    static Map<yacyURL, String> allSubpaths(Collection<?> links) {
        // links is either a Set of Strings (urls) or a Set of htmlFilterImageEntries
        HashSet<String> h = new HashSet<String>();
        Iterator<?> i = links.iterator();
        Object o;
        yacyURL url;
        String u;
        int pos;
        int l;
        while (i.hasNext()) try {
            o = i.next();
            if (o instanceof yacyURL) url = (yacyURL) o;
            else if (o instanceof String) url = new yacyURL((String) o, null);
            else if (o instanceof htmlFilterImageEntry) url = ((htmlFilterImageEntry) o).url();
            else {
                assert false;
                continue;
            }
            u = url.toNormalform(true, true);
            if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            pos = u.lastIndexOf('/');
            while (pos > 8) {
                l = u.length();
                u = u.substring(0, pos + 1);
                h.add(u);
                u = u.substring(0, pos);
                assert (u.length() < l) : "u = " + u;
                pos = u.lastIndexOf('/');
            }
        } catch (MalformedURLException e) {}
        // now convert the strings to yacyURLs
        i = h.iterator();
        HashMap<yacyURL, String> v = new HashMap<yacyURL, String>();
        while (i.hasNext()) {
            u = (String) i.next();
            try {
                url = new yacyURL(u, null);
                v.put(url, "sub");
            } catch (MalformedURLException e) {}
        }
        return v;
    }
    
    public static void main(String[] args) {
        //javac -sourcepath source source/de/anomic/plasma/plasmaParser.java
        //java -cp source de.anomic.plasma.plasmaParser bug.html bug.out
        try {
            Object content = null;
            yacyURL contentURL = null;
            long contentLength = -1;
            String contentMimeType = "application/octet-stream";
            String charSet = "utf-8";
            
            if (args.length < 2) {
                System.err.println("Usage: java de.anomic.plasma.plasmaParser (-f filename|-u URL) [-m mimeType]");
            }            
                        
            String mode = args[0];
            JakartaCommonsHttpResponse res = null;
            plasmaParserDocument document = null;
            try { // close InputStream when done
            if (mode.equalsIgnoreCase("-f")) {
                content = new File(args[1]);
                contentURL = new yacyURL((File)content);
            } else if (mode.equalsIgnoreCase("-u")) {
                contentURL = new yacyURL(args[1], null);
                
                // downloading the document content
                JakartaCommonsHttpClient client = new JakartaCommonsHttpClient(5000, null, null);
                
                res = client.GET(args[1]);
                if (res.getStatusCode() != 200) {
                    System.err.println("Unable to download " + contentURL + ". " + res.getStatusLine());
                    return;
                }
                content = res.getDataAsStream();
                contentMimeType = res.getResponseHeader().mime();
                charSet = res.getResponseHeader().getCharacterEncoding();
                contentLength = res.getResponseHeader().contentLength();
            }
            
            if ((args.length >= 4)&&(args[2].equalsIgnoreCase("-m"))) {
                contentMimeType = args[3];
            }
            
            if ((args.length >= 6)&&(args[4].equalsIgnoreCase("-c"))) {
                charSet = args[5];
            }            
            
            // creating a plasma parser
            plasmaParser theParser = new plasmaParser();
            
            // configuring the html parsable mimeTypes
            plasmaParser.initHTMLParsableMimeTypes("application/xhtml+xml,text/html,text/plain,text/sgml");

            // parsing the content
            if (content instanceof byte[]) {
                document = theParser.parseSource(contentURL, contentMimeType, charSet, (byte[])content);
            } else if (content instanceof File) {
                document = theParser.parseSource(contentURL, contentMimeType, charSet, (File)content);
            } else if (content instanceof InputStream) {
            	document = theParser.parseSource(contentURL, contentMimeType, charSet, contentLength, (InputStream)content);
            }
            } finally {
                if(res != null) {
                    res.closeStream();
                }
            }

            // printing out all parsed sentences
            if (document != null) {
                System.out.print("Document titel: ");
                System.out.println(document.dc_title());
                
                // found text
                final Iterator<StringBuffer> sentences = document.getSentences(false);
                int i = 0;
                if (sentences != null) while (sentences.hasNext()) {
                        System.out.print("line " + i + ": ");
                        System.out.println(sentences.next().toString());
                        i++;
                }
                
                // found links
                int anchorNr = 0;
                Map<yacyURL, String> anchors = document.getAnchors();
                Iterator<yacyURL> anchorIter = anchors.keySet().iterator();
                while (anchorIter.hasNext()) {
                    yacyURL key = anchorIter.next();
                    System.out.println("URL " + anchorNr + ":\t" + key.toString() + " | " + anchors.get(key));
                    anchorNr++;
                }
                document.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static boolean supportedContent(yacyURL url, String mimeType) {
        if (url == null) throw new NullPointerException();
        
        Iterator<plasmaParserConfig> configs = parserConfigList.values().iterator();
        while (configs.hasNext()) {
            plasmaParserConfig currentConfig = configs.next();
            synchronized (currentConfig.enabledParserList) {
                if (currentConfig.supportedContent(url, mimeType)) return true;
            }
        }        
        
        return false;
    }    

    public static boolean supportedContent(String parserMode, yacyURL url, String mimeType) {
        if (!PARSER_MODE.contains(parserMode)) throw new IllegalArgumentException();
        if (url == null) throw new NullPointerException();
        
        if (parserMode.equals(PARSER_MODE_IMAGE)) return true;
        plasmaParserConfig config = (plasmaParserConfig) parserConfigList.get(parserMode);
        return (config == null)?false:config.supportedContent(url, mimeType);
    }

    public static void initParseableMimeTypes(String parserMode, String configStr) {
        if (!PARSER_MODE.contains(parserMode)) throw new IllegalArgumentException();
        
        plasmaParserConfig config = (plasmaParserConfig) parserConfigList.get(parserMode);
        if (config == null) {
            config = new plasmaParserConfig(parserMode);
            parserConfigList.put(parserMode, config);
        }
        config.initParseableMimeTypes(configStr);
    }

    public static String[] setEnabledParserList(String parserMode, Set<String> mimeTypeSet) {
        if (!PARSER_MODE.contains(parserMode)) throw new IllegalArgumentException();
        
        plasmaParserConfig config = (plasmaParserConfig) parserConfigList.get(parserMode);
        if (config == null) {
            config = new plasmaParserConfig(parserMode);
            parserConfigList.put(parserMode, config);
        }
        return config.setEnabledParserList(mimeTypeSet);        
    }
    
    public static boolean supportedFileExtContains(String fileExt) {
        Iterator<plasmaParserConfig> configs = parserConfigList.values().iterator();
        while (configs.hasNext()) {
            plasmaParserConfig currentConfig = configs.next();
            synchronized (currentConfig.enabledParserList) {
                if (currentConfig.supportedFileExtContains(fileExt)) return true;
            }
        }        
        
        return false;
    }

    public static boolean supportedMimeTypesContains(String mimeType) {
        Iterator<plasmaParserConfig> configs = parserConfigList.values().iterator();
        while (configs.hasNext()) {
            plasmaParserConfig currentConfig = configs.next();
            synchronized (currentConfig.enabledParserList) {
                if (currentConfig.supportedMimeTypesContains(mimeType)) return true;
            }
        }        
        
        return false;
    }    

    public static Parser makeParser(Object name) throws Exception {
        
        if (!(name instanceof String))
            throw new IllegalArgumentException("The object key must be of type string.");
        
        // loading class by name
        Class<?> moduleClass = Class.forName((String)name);
        
        // instantiating class
        Parser theParser = (Parser) moduleClass.newInstance();
        
        // setting logger that should by used
        String parserShortName = ((String)name).substring("de.anomic.plasma.parser.".length(),((String)name).lastIndexOf("."));
        
        serverLog theLogger = new serverLog("PARSER." + parserShortName.toUpperCase());
        theParser.setLogger(theLogger);
        
        return theParser;
    }          
   
}
