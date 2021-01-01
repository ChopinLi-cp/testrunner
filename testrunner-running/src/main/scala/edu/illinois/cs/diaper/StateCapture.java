package edu.illinois.cs.diaper;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.reflection.FieldDictionary;
import com.thoughtworks.xstream.converters.reflection.FieldKey;
import com.thoughtworks.xstream.converters.reflection.FieldKeySorter;
import com.thoughtworks.xstream.converters.reflection.PureJavaReflectionProvider;
import com.thoughtworks.xstream.io.xml.DomDriver;

import edu.illinois.cs.diaper.agent.MainAgent;
import edu.illinois.cs.diaper.DiaperLogger;

import java.io.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;

import org.apache.maven.project.MavenProject;
import org.custommonkey.xmlunit.DetailedDiff;
import org.custommonkey.xmlunit.Diff;
import org.custommonkey.xmlunit.Difference;
import org.custommonkey.xmlunit.NodeDetail;
import org.custommonkey.xmlunit.XMLUnit;
import java.lang.Object;

import java.nio.charset.StandardCharsets;
import org.apache.commons.io.FileUtils;
import org.w3c.dom.Document;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.InputSource;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.OutputKeys;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Element;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathConstants;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class StateCapture implements IStateCapture {


    protected final String testName;
    protected final List<String> currentTestStates = new ArrayList<String>();
    protected final List<Set<String>> currentRoots = new ArrayList<Set<String>>();

    private DocumentBuilder dBuilder = null;
    public boolean dirty;
    private boolean classLevel;

    // The State, field name of static root to object pointed to
    private static final LinkedHashMap<String, Object> nameToInstance = new LinkedHashMap<String, Object>();
    private static final boolean shouldIgnore = true;
    protected static final int MAX_NUM = 4;    
    private static final boolean verbose;
    private static final String logFilePath;
    private static final String projectName;
    private static final boolean enableLogging;
    private static final DiaperLogger logger;
    private static final int execSize = Runtime.getRuntime().availableProcessors();
    private static ExecutorService executor = Executors.newFixedThreadPool(execSize);
    private static final Set<String> whiteList;
    private static final Set<String> ignores;
    private static Queue<Future<?>> submittedTasks = new LinkedList<Future<?>>();

    //for reflection and deserialzation
    private int xmlFileNum;
    private Set<String> diffFields = new HashSet<String> ();
    private Set<String> diffFields_filtered = new HashSet<String> ();
    public String xmlFold;
    public String subxmlFold;
    public String rootFold;
    public String diffFold;
    public String slug;
    public String reflectionFile;

    static { 
        Properties p = System.getProperties();
        // Default if missing is false
        if (p.getProperty("verbose") == null) {
            verbose = false;
        }
        else {
            verbose = ((p.getProperty("verbose").equals("1")) ? true : false);
        }
    
        // Check if time logging is requested
        if (p.getProperty("logFile") == null) {
            enableLogging = false;
            logFilePath = "LOG_logfile";
        }
        else {
            enableLogging = true;
            logFilePath = p.getProperty("logFile");
        }
        if (p.getProperty("projName") == null) {
            projectName = "NO_NAME";
        }
        else {
            projectName = p.getProperty("projName");
        }

        logger = new DiaperLogger(projectName, logFilePath);

        Runnable r = new Runnable() {
                public void run() {
                    try { 
                        executor.shutdown();
                        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
                    } catch(Exception ex) {
                        ex.printStackTrace();
                    }
                }
            };
        Thread t = new Thread(r);
        Runtime.getRuntime().addShutdownHook(t);
        XMLUnit.setNormalizeWhitespace(Boolean.TRUE);

        whiteList = fileToSet(p, "whitelist");
        ignores = fileToSet(p, "ignores");
    }

    public static void awaitTermination() {
        try { 
            executor.shutdown();
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
            executor = Executors.newFixedThreadPool(execSize);
            for(Future f : submittedTasks) {
                try{
                    f.get();
                } catch (Throwable t) {
                    t.printStackTrace();
                }

            }

            submittedTasks = new LinkedList<Future<?>>();
        } catch(Exception ex) {
            ex.printStackTrace();
        }
        
    }

    

    private static Set<String> fileToSet(Properties p, String name) {
        String fn = p.getProperty(name);
        System.out.println("*****************fn: " + fn);
        Set<String> wl = new HashSet<>();
        if (fn == null) {
            return wl;
        } else {
            try (BufferedReader br = new BufferedReader(new FileReader(fn))) {
                    for(String line; (line = br.readLine()) != null; ) {
                        // Support for comments (line starts with #)
                        // Essentially only add if it does not start with #
                        if (!line.startsWith("#")) {
                            wl.add(line);
                        }
                    }
                    return Collections.unmodifiableSet(wl);
                } catch (IOException ex) {
                ex.printStackTrace();
                throw new RuntimeException(ex);
            }
        }
    }

    public StateCapture(String testName) {
        this.testName = testName;
        try {
            dBuilder = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder();
        } catch(ParserConfigurationException ex) {
            ex.printStackTrace();
        }
    }

    // Constructor to control if it's a class-level state capture
    public StateCapture(String testName, boolean classLevel) {
        this(testName);
        this.classLevel = classLevel;
    }

    private void initialize() throws IOException {
        //run capture 40 times to stabilize things a bit
        for (int i = 0; i < 40; i++) {
            // create new instance to avoid writing to file
            StateCapture sc = new StateCapture("diaper@initialization -- dummy" + i);
            sc.capture();         
        }
    }

    public static IStateCapture instanceFor(String entityName) {
        return new StateCapture(entityName);
    }

    // Factory instance for setting class level
    public static IStateCapture instanceFor(String entityName, boolean classLevel) {
        return new StateCapture(entityName, classLevel);
    }

    /**
     * Call this method to capture the state, save it, and do the diffing if all required
     * states for the current test have been captured.
     * @throws IOException 
     */
    public void runCapture() {


        //call capture() and add the state map to currentTestStates
        //capture();
        // if we already captured 4 states for the current test, then we are ready to diff
        if (currentTestStates.size() == MAX_NUM) {
            if (enableLogging) {
                logger.startTimer();
            }
            //diffPairs();
            if (enableLogging) {
                logger.stopTimeAndUpdate(DiaperLogger.Task.DIFF);
                logger.saveToFileAndReset();
            }
        }
    }


    /**
     * Removes the fields in the before state and not in the after state.
     *
     * @param  beforeState  string representing the 'before' state
     * @param  beforeRoots  set of the root static fields for the 'before' state
     * @param  afterState   string representing the 'after' state
     * @param  afterRoots   set of the root static fields for the 'after' state
     * @return              string representing the 'after' state with only the common fields with
     *                      the 'before' state
     */
    // If there are any extra fields in after not in before, add them
    private String checkAdded(String beforeState, Set<String> beforeRoots, 
                              String afterState, Set<String> afterRoots) {
        Set<String> rootsDifference = new HashSet<String>(afterRoots);
        rootsDifference.removeAll(beforeRoots);

        if (rootsDifference.isEmpty()) {
            return afterState;
        }

        Document after = stringToXmlDocument(afterState);
        Element root = after.getDocumentElement();
        NodeList ls = root.getChildNodes();
        for (int i = 0; i < ls.getLength(); i++) {
            Node n = ls.item(i);
            if (n.getNodeName().equals("entry")) {
                Node keyNode = n.getChildNodes().item(1);
                if (rootsDifference.contains(keyNode.getTextContent())) {
                    Node tmp = n.getPreviousSibling();
                    root.removeChild(n);
                    root.removeChild(tmp);
                    i = i - 2;
                }
            }
        }

        if (ls.getLength() == 1) {
            root.removeChild(ls.item(0));
        }
        
        return documentToString(after);
    }

    private Document cloneDocument(Document doc) {
        try {
            TransformerFactory tfactory = TransformerFactory.newInstance();
            Transformer tx   = tfactory.newTransformer();
            DOMSource source = new DOMSource(doc);
            DOMResult result = new DOMResult();
            tx.transform(source,result);
            return (Document)result.getNode();
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    private void cleanupDocument(Document doc) {
        try{
            XPath xp = XPathFactory.newInstance().newXPath();
            NodeList nl = (NodeList) xp.evaluate("//text()[normalize-space(.)='']", 
                                                 doc, XPathConstants.NODESET);

            for (int i=0; i < nl.getLength(); ++i) {
                Node node = nl.item(i);
                node.getParentNode().removeChild(node);
            }   
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Performs the diff between the two passed state maps and saves it into a file.
     * If 'verbose' is specified, a detailed diff is also performed and appended to the file.
     *
     * @param  testname     name of the test currently diffing
     * @param  beforeState  string representing the 'before' state
     * @param  beforeRoots  set of the root static fields for the 'before' state
     * @param  afterState   string representing the 'after' state
     * @param  afterRoots   set of the root static fileds for the 'after' state
     * @param  fileName     name of the output file in which we save the diff
     */
    private void recordDiff(String testname, String beforeState, Set<String> beforeRoots,
                                String afterState, Set<String> afterRoots, String fileName) {

        // returns a new afterState only having the roots that are common with the beforeState
        afterState = checkAdded(beforeState, beforeRoots, afterState, afterRoots);
        try {
            boolean statesAreSame = beforeState.equals(afterState);
            // create a string builder
            StringBuilder sb = new StringBuilder();
            sb.append(testname);
            sb.append(" ");
            sb.append(statesAreSame);
            sb.append("\n");

            /*if (!statesAreSame) {
                writeToFile(fileName + "_" + testname.replaceAll(" ", "_")
                        + "_before.xml", beforeState, false);
                writeToFile(fileName + "_" + testname.replaceAll(" ", "_")
                        + "_after.xml", afterState, false);
            }*/

            ///*if (this.verbose) {
            Diff diff = new Diff(beforeState, afterState);
            DetailedDiff detDiff = new DetailedDiff(diff);
            List differences = detDiff.getAllDifferences();
            Collections.sort(differences, new Comparator() {
                public int compare(Object o1, Object o2) {
                    Difference d1 = (Difference)o1;
                    Difference d2 = (Difference)o2;
                    // Sort based on id, which should represent order in the XML
                    if (d1.getId() < d2.getId()) {
                        return -1;
                    }
                    else if (d1.getId() == d2.getId()) {
                        return 0;
                    }
                    else {
                        return 1;
                    }
                }
            });
            for (Object object : differences) {
                Difference difference = (Difference)object;

                sb.append("***********************\n");
                sb.append(difference);
                sb.append("\n~~~~\n");
                makeDifferenceReport(difference, beforeState, sb);
                sb.append("***********************\n");
            }
            // }*/
            writeToFile(fileName, sb.toString(), true);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void recordsubDiff(String testname, String beforeState,
                            String afterState, String fileName) {

        // returns a new afterState only having the roots that are common with the beforeState
        //afterState = checkAdded(beforeState, beforeRoots, afterState, afterRoots);
        try {
            boolean statesAreSame = beforeState.equals(afterState);
            // create a string builder
            StringBuilder sb = new StringBuilder();
            /*sb.append(testname);
            sb.append(" ");
            sb.append(statesAreSame);
            sb.append("\n");*/

            Diff diff = new Diff(beforeState, afterState);
            DetailedDiff detDiff = new DetailedDiff(diff);
            List differences = detDiff.getAllDifferences();
            Collections.sort(differences, new Comparator() {
                public int compare(Object o1, Object o2) {
                    Difference d1 = (Difference)o1;
                    Difference d2 = (Difference)o2;
                    // Sort based on id, which should represent order in the XML
                    if (d1.getId() < d2.getId()) {
                        return -1;
                    }
                    else if (d1.getId() == d2.getId()) {
                        return 0;
                    }
                    else {
                        return 1;
                    }
                }
            });
            for (Object object : differences) {
                Difference difference = (Difference)object;

                sb.append("***********************\n");
                sb.append(difference);
                sb.append("\n~~~~\n");
                makeSubDifferenceReport(difference, beforeState, sb);
                sb.append("***********************\n");
            }

            writeToFile(fileName, sb.toString(), true);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    /**
     * From THE INTERNET :)
     **/
    private String documentToString(Document doc) {
        try{
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            StringWriter sw = new StringWriter();
            StreamResult result = new StreamResult(sw);
            DOMSource source = new DOMSource(doc);
            transformer.transform(source, result);
            String str = sw.toString();
            str = str.trim();
            str = str.substring(str.indexOf('\n') + 1);
            return str;
        } catch (Exception ex) {
            ex.printStackTrace();
            return "";
        }
    }

    /**
     * Writes content into a file.
     *
     * @param  fn       name of the destination file
     * @param  content  string representing the data to be written
     * @param  append   boolean indicating whether to append to destination file or rewrite it
     */
    protected void writeToFile(String fn, String content, boolean append) {
        synchronized(StateCapture.class) {
            try {
                File f = new File(fn);
                f.createNewFile();

                FileWriter fw = new FileWriter(f.getAbsoluteFile(), append);
                BufferedWriter w = new BufferedWriter(fw);
                w.write(content);
                w.close();
                // fw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    Set<String> File2SetString(String path) {
        File file = new File(path);
        Set<String> keys = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                keys.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return keys;
    }

    private void diffSub() throws FileNotFoundException, UnsupportedEncodingException {
        String subxml0 = subxmlFold + "/0xml";
        String subxml1 = subxmlFold + "/1xml";
        String afterRootPath= rootFold + "/1.txt";
        Set<String> afterRoots = File2SetString(afterRootPath);

        for(String s: afterRoots) {
            //System.out.println("afterroot name: " + s);
            String path0 = subxml0 + "/" + s + ".xml";
            String path1 = subxml1 + "/" + s + ".xml";
            String state0 = ""; String state1 = "";
            File file0 = new File(path0);
            if(!file0.exists())
                System.out.println("the field name " + s + " exists in the afterRoot " +
                        "but does not exist in the beforeRoot");
            else {
                try{
                    state0 = readFile(path0);
                    state1 = readFile(path1);
                }
                catch(IOException e) {
                    System.out.println("error in reading subxmls: " + e);
                }

                if (!state0.equals(state1)) {
                    diffFields_filtered.add(s);
                    String subdiffFile = MainAgent.subdiffFold + "/" + s + ".txt";
                    recordsubDiff(testName, state0, state1, subdiffFile);
                }
            }
        }

        int num = new File(MainAgent.diffFieldFold).listFiles().length;
        PrintWriter writer = new PrintWriter(MainAgent.diffFieldFold + "/" + num+ ".txt", "UTF-8");

        for(String ff: diffFields_filtered) {
            writer.println(ff);
        }
        writer.close();
    }

    private void outputStatstics() {
        String subxml0 = subxmlFold + "/0xml";
        String subxml1 = subxmlFold + "/1xml";

        try {
            String allfieldsPath = MainAgent.fieldFold + "/0.txt";
            BufferedReader reader = new BufferedReader(new FileReader(allfieldsPath));
            int allfields = 0;
            while (reader.readLine() != null) allfields++;
            reader.close();

            String statistics = slug + "," + testName + "," + allfields
                    + "," + countFiles(subxml0) + "," + countFiles(subxml1) + "," + diffFields.size()
                + "," + diffFields_filtered.size() + "\n";

            Files.write(Paths.get(MainAgent.outputPath), statistics.getBytes(),
                    StandardOpenOption.APPEND);

        } catch (Exception e) {

            System.out.println("exception in outputStatstics: " + e);
            //exception handling left as an exercise for the reader
        }
    }

    private LinkedHashMap<String, Object> deserialize() {
        LinkedHashMap<String, Object> f2o_correct =
                new LinkedHashMap<String, Object>();
        String subxml0 = subxmlFold + "/0xml";
        try {
            for (String s : diffFields_filtered) {
                System.out.println("field: " + s);
                String path0 = subxml0 + "/" + s + ".xml";
                String state0 = readFile(path0);

                XStream xstream = new XStream();
                Object ob_0 = xstream.fromXML(state0);
                f2o_correct.put(s, ob_0);
            }
        }
        catch(Exception e) {
            System.out.println("error in xml deserialztion: " + e);
        }

        return f2o_correct;
    }

    private void reflection(LinkedHashMap<String, Object> f2o) {
            for(String fieldName: f2o.keySet()) {
                System.out.println("***field in f2o: " + fieldName);
                System.out.println("object in f2o: " + f2o.get(fieldName));
                String className = fieldName.substring(0, fieldName.lastIndexOf("."));
                String subFieldName = fieldName.substring(fieldName.lastIndexOf(".")+1, fieldName.length());
                System.out.println("subFieldName: " + subFieldName);
                System.out.println("className: " + className);
                Object ob = f2o.get(fieldName);
                try{
                    Class c = Class.forName(className);
                    Field[] Flist = c.getDeclaredFields();
                    for(int i=0; i< Flist.length; i++) {
                       //System.out.println("Flist[i].getName(): " + Flist[i].getName());
                        if(Flist[i].getName().equals(subFieldName)) {
                                //&& subFieldName.contains("CONNECTION_FACTORY")) {
                            try{
                                Flist[i].setAccessible(true);
                                Flist[i].set(null, ob);
                                System.out.println("set!!!");
                            }
                            catch(Exception e) {
                                System.out.println("exception in setting " +
                                        "field with reflaction: " + e);
                            }

                            break;
                        }
                    }
                }
                catch(Exception e){
                    System.out.println("class for name exception: " + e);
                }
            }
    }

    public void setup() {
        xmlFold = MainAgent.xmlFold;
        subxmlFold = MainAgent.subxmlFold;
        rootFold = MainAgent.rootFold;
        diffFold = MainAgent.diffFold;
        slug = MainAgent.slug;
        reflectionFile = MainAgent.reflectionFold + "/0.txt";
        xmlFileNum = new File(xmlFold).listFiles().length;
        System.out.println("xmlFileName: " + xmlFileNum);

    }

    public void diffing() {
        setup();
        try {
            //diffPairs();
            diffSub();
        }
        catch (Exception e){
            System.out.println("diff error: " + e) ;
        }
        System.out.println("diffing done!!");
    }

    public void fixing(String fieldName) throws IOException {
        setup();
        String subxml0 = subxmlFold + "/0xml";
        try {
                System.out.println("field: " + fieldName);
                String path0 = subxml0 + "/" + fieldName + ".xml";
                String state0 = readFile(path0);
                XStream xstream = new XStream();
                Object ob_0 = xstream.fromXML(state0);

                String className = fieldName.substring(0, fieldName.lastIndexOf("."));
                String subFieldName = fieldName.substring(fieldName.lastIndexOf(".")+1, fieldName.length());
                System.out.println("subFieldName: " + subFieldName);
                System.out.println("className: " + className);

                try{
                    Class c = Class.forName(className);
                    Field[] Flist = c.getDeclaredFields();
                    for(int i=0; i< Flist.length; i++) {
                        //System.out.println("Flist[i].getName(): " + Flist[i].getName());
                        if(Flist[i].getName().equals(subFieldName)) {
                            try{
                                Flist[i].setAccessible(true);
                                Flist[i].set(null, ob_0);
                                System.out.println("set!!!");
                            }
                            catch(Exception e) {
                                System.out.println("exception in setting " +
                                        "field with reflaction: " + e);
                                String output = fieldName + " reflectionError\n";
                                Files.write(Paths.get(reflectionFile), output.getBytes(),
                                        StandardOpenOption.APPEND);
                            }
                            break;
                        }
                    }
                }
                catch(Exception e){
                    System.out.println("error in reflection: " + e);
                }
        }
        catch(Exception e) {
            System.out.println("error in xml deserialztion: " + e);
            String output = fieldName + " deserializeError\n";
            Files.write(Paths.get(reflectionFile), output.getBytes(),
                    StandardOpenOption.APPEND);
        }
        System.out.println("reflection done!!");
    }

    public void capture() {
        setup();
        try {
            capture_real();
        }
        catch(Exception e) {
            System.out.println("error happened when doing capture real: " + e);
        }
        System.out.println("capture_real done!!");
    }


    //
    /**
     * Adds the current serialized reachable state to the currentTestStates list
     * and the current roots to the currentRoots list.
     * @throws IOException
     */
    public void capture_real() throws IOException {

        //read whitelist;
        try (BufferedReader br = new BufferedReader(new FileReader(MainAgent.pkgFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                //System.out.println("whilteList!!!!!!!!!!!!: " + line);
                whiteList.add(line);
            }
        }
        catch (Exception e) {
            System.out.println("error while read pkg-filter file!!!");
            return;
        }

        String subxmlDir = createSubxmlFold();

        Set<String> allFiledName = new HashSet<String>();
        Class[] loadedClasses = MainAgent.getInstrumentation().getAllLoadedClasses();

        for (Class c : loadedClasses) {
            // Ignore classes in standard java to get top-level
            // TODO(gyori): make this read from file or config option
            String clz = c.getName();
            if (clz.contains("java.")
                || clz.contains("javax.")
                || clz.contains("jdk.")
                || clz.contains("scala.")
                || clz.contains("sun.")
                || clz.contains("edu.illinois.cs")
                || clz.contains("org.custommonkey.xmlunit")
                || clz.contains("org.junit")
                || clz.contains("diaper.com.")
                || clz.contains("diaper.org.")) {
                continue;
            }

            Set<Field> allFields = new HashSet<Field>();
            try {
                Field[] declaredFields = c.getDeclaredFields();
                Field[] fields = c.getFields();
                allFields.addAll(Arrays.asList(declaredFields));
                allFields.addAll(Arrays.asList(fields));
            } catch (NoClassDefFoundError e) {

                continue;
            }
            // prepare for the subxml fold


            for (Field f : allFields) {
                String fieldName = getFieldFQN(f);

                //System.out.println("$$$$$$$$$$$$fieldName: " + fieldName);
                if (ignores.contains(fieldName)) {
                    //System.out.println("$$$$$$$$$$$$IGNORE$$$$$$$$ fieldName: " + fieldName);
                    continue;
                }
                // if a field is final and has a primitive type there's no point to capture it.
                if (Modifier.isStatic(f.getModifiers())
                    && !(Modifier.isFinal(f.getModifiers()) &&  f.getType().isPrimitive())) {
                    try {
                        if (shouldCapture(f)) {
                               allFiledName.add(fieldName);
                               f.setAccessible(true);

                               //System.out.println("f.getType(): "+f.getType());
                               Object instance = f.get(null);
                               LinkedHashMap<String, Object> nameToInstance_temp = new LinkedHashMap<String, Object>();
                               nameToInstance_temp.put(fieldName, instance);

                               dirty = false;
                               serializeRoots(nameToInstance_temp);
                               if(!dirty) {
                                   //System.out.println("nameToInstance^^^^^^^^^^^^^^^^fieldName: " +
                                     //      fieldName);
                                   nameToInstance.put(fieldName, instance);

                                   String ob4field = serializeOBs(instance);
                                   PrintWriter writer = new PrintWriter(subxmlDir + "/" + fieldName + ".xml", "UTF-8");
                                   writer.println(ob4field);
                                   writer.close();
                               }
                              // System.out.println("end");
                            }
                    } catch (NoClassDefFoundError e) {
                    	continue;
                    } catch (Exception e) {
                        System.out.println("error in capture real: " + e);
                        //e.printStackTrace();
                        continue;
                    }
                }
            }
        }
        String serializedState = serializeRoots(nameToInstance);
        System.out.println("xmlFold: " + xmlFold);

        System.out.println("@@@@@@@@@@testname:" + testName);
        int num = new File(xmlFold).listFiles().length;

        PrintWriter writer = new PrintWriter(xmlFold + "/" + num+ ".xml", "UTF-8");
        writer.println(serializedState);
        writer.close();

        writer = new PrintWriter(rootFold + "/" + num+ ".txt", "UTF-8");
        for(String key: nameToInstance.keySet()) {
            writer.println(key);
        }
        writer.close();

        num = new File(MainAgent.fieldFold).listFiles().length;
        writer = new PrintWriter(MainAgent.fieldFold + "/" + num+ ".txt", "UTF-8");
        for(String ff: allFiledName) {
            writer.println(ff);
        }
        writer.close();

    }

    protected boolean shouldCapture(Field f) {
        // previous code************
        /*String fieldName = getFieldFQN(f);
        String fldLower = fieldName.toLowerCase();

        if (fldLower.contains("mockito") ||
            fldLower.contains("$$")) {
            //System.out.println("***Ignored_Root: " + fieldName);
            return false;
        }

        Package p = f.getDeclaringClass().getPackage();
        //System.out.println("&&&&&&&&&package: " + p + " whiteList: " + whiteList
          //      + " p.getName: " + p.getName());
        if (p!=null) {
            String pkg = p.getName();
            if (!whiteList.contains(pkg)) {
                return false;
            }
        }

        return true;*/
        //previous code*********
        return true;
    }

    String createSubxmlFold() {
        int subxmlDirCnt = 0;
        File f = new File(subxmlFold);
        File[] files = f.listFiles();
        if (files != null)
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    subxmlDirCnt++;
                }
            }

        String subxmlDir = subxmlFold + "/" + subxmlDirCnt +"xml";
        File theDir = new File(subxmlDir);
        if (!theDir.exists()){
            theDir.mkdirs();
        }
        return subxmlDir;
    }

    private void diffPairs() throws IOException {

        String beforeState = readFile(xmlFold + "/0.xml");
        String afterState = readFile(xmlFold + "/1.xml");
        String beforeRootPath= rootFold + "/0.txt";
        String afterRootPath= rootFold + "/1.txt";
        Set<String> beforeRoots = File2SetString(beforeRootPath);
        Set<String> afterRoots = File2SetString(afterRootPath);
        String diffFileName = diffFold + "/diff";
        System.out.println("diffFilename: " + diffFileName);
        recordDiff(testName, beforeState, beforeRoots, afterState, afterRoots, diffFileName);
    }

    private void makeDifferenceReport(Difference difference, String xmlDoc, StringBuilder sb) {
        NodeDetail controlNode = difference.getControlNodeDetail();
        NodeDetail afterNode = difference.getTestNodeDetail();

        String diffXpath = controlNode.getXpathLocation();
        if (diffXpath == null) {
            diffXpath = afterNode.getXpathLocation();
            if (diffXpath == null) {
                sb.append("NULL xpath\n");
                return;
            }
        }
        sb.append(controlNode.getXpathLocation());
        sb.append("\n");
        sb.append(afterNode.getXpathLocation());
        sb.append("\n");

        String[] elems = diffXpath.split("/");
        if (elems.length >= 3) {
            diffXpath = "/" + elems[1] + "/" + elems[2];
            try {
                XPath xPath =  XPathFactory.newInstance().newXPath();
                Node n = (Node) xPath.compile(diffXpath).evaluate(stringToXmlDocument(xmlDoc), XPathConstants.NODE);
                n = n.getChildNodes().item(1);
                sb.append("Static root: ");
                String fieldD = n.getTextContent();
                sb.append(fieldD);
                sb.append("\n");
                sb.append("AUGUST ID: " + difference.getId());
                sb.append("\n");
                diffFields.add(fieldD);
            } catch (Exception ex) {
                //ex.printStackTrace();
                System.out.println("exception in makedifferencereport!!" + ex);
            }
        }
    }

    private void makeSubDifferenceReport(Difference difference, String xmlDoc, StringBuilder sb) {
        NodeDetail controlNode = difference.getControlNodeDetail();
        NodeDetail afterNode = difference.getTestNodeDetail();

        String diffXpath = controlNode.getXpathLocation();
        if (diffXpath == null) {
            diffXpath = afterNode.getXpathLocation();
            if (diffXpath == null) {
                sb.append("NULL xpath\n");
                return;
            }
        }
        sb.append(controlNode.getXpathLocation());
        sb.append("\n");
        sb.append(afterNode.getXpathLocation());
        sb.append("\n");

        /*String[] elems = diffXpath.split("/");
        if (elems.length >= 3) {
            diffXpath = "/" + elems[1] + "/" + elems[2];
            try {
                XPath xPath =  XPathFactory.newInstance().newXPath();
                Node n = (Node) xPath.compile(diffXpath).evaluate(stringToXmlDocument(xmlDoc), XPathConstants.NODE);
                n = n.getChildNodes().item(1);
                sb.append("Static root: ");
                String fieldD = n.getTextContent();
                sb.append(fieldD);
                sb.append("\n");
                sb.append("AUGUST ID: " + difference.getId());
                sb.append("\n");
                //diffFields.add(fieldD);
            } catch (Exception ex) {
                //ex.printStackTrace();
                System.out.println("exception in makedifferencereport!!" + ex);
            }
        }*/
    }

    /**
     * Takes in a string and removes problematic characters.
     *
     * @param  in  the input string to be filtered
     * @return     the input string with the unparsable characters removed
     */
    public static String sanitizeXmlChars(String in) {
        in = in.replaceAll("&#", "&amp;#");
        StringBuilder out = new StringBuilder();
        char current;

        if (in == null || ("".equals(in)))
            return "";
        for (int i = 0; i < in.length(); i++) {
            current = in.charAt(i);
            if ((current == 0x9) ||
                (current == 0xA) ||
                (current == 0xD) ||
                ((current >= 0x20) && (current <= 0xD7FF)) ||
                ((current >= 0xE000) && (current <= 0xFFFD)) ||
                ((current >= 0x10000) && (current <= 0x10FFFF)))
                out.append(current);
        }
        return out.toString();
    }

    /**
     * This is the method that calls XStream to serialize the state map into a string.
     *
     * @param  state  the string to object map representing the roots of the state
     * @return        string representing the serialized input state
     */
    private String serializeRoots(Map<String, Object> state) {
        XStream xstream = getXStreamInstance();
        String s = "";
        System.out.println(state);
        try {
            s = xstream.toXML(state);
            s = sanitizeXmlChars(s);
        } catch (Exception e) {
            // In case serialization fails, mark the StateCapture for this test
            // as dirty, meaning it should be ignored
            System.out.println("!!!!!!!!!!!!!!!!!!!!&&&&&&&&&&&&&&&error: " + e);
            dirty = true;
            //throw e;
        }
        return s;
    }

    private String serializeOBs(Object ob) {
        XStream xstream = getXStreamInstance();
        String s = "";
        try {
            s = xstream.toXML(ob);
            s = sanitizeXmlChars(s);
        } catch (Exception e) {
            // In case serialization fails, mark the StateCapture for this test
            // as dirty, meaning it should be ignored
            System.out.println("!!!!!!!!!!!!!!!!!!!!&&&&&&&&&&&&&&&error: " + e);
            dirty = true;
            throw e;
        }
        return s;
    }

    private static class AlphabeticalFieldkeySorter implements FieldKeySorter {
        @Override
        public Map sort(Class type, Map keyedByFieldKey) {
            final Map<FieldKey, Field> map = new TreeMap<>(new Comparator<FieldKey>() {

                @Override
                public int compare(final FieldKey fieldKey1, final FieldKey fieldKey2) {
                    return fieldKey1.getFieldName().compareTo(fieldKey2.getFieldName());
                }
            });
            map.putAll(keyedByFieldKey);
            return map;
        }
    }

    private XStream getXStreamInstance() {
        //XStream xstream = new XStream(new DomDriver());
        XStream xstream = new XStream(new PureJavaReflectionProvider(new FieldDictionary(
                new AlphabeticalFieldkeySorter())),new DomDriver());
        xstream.setMode(XStream.XPATH_ABSOLUTE_REFERENCES);
        // Set fields to be omitted during serialization
        xstream.omitField(java.lang.ref.SoftReference.class, "timestamp");
        xstream.omitField(java.lang.ref.SoftReference.class, "referent");
        xstream.omitField(java.lang.ref.Reference.class, "referent");

        /*
          String ignores[][] = new String[][] {
          {"com.squareup.wire.Wire", "messageAdapters"},
          {"com.squareup.wire.Wire", "builderAdapters"},
          {"com.squareup.wire.Wire", "enumAdapters"},
          {"org.apache.http.impl.conn.CPool", "COUNTER"},
          {"org.apache.http.impl.conn.ManagedHttpClientConnectionFactory", "COUNTER"},
          {"org.apache.http.localserver.LocalTestServer", "TEST_SERVER_ADDR"},
          {"org.apache.http.impl.auth.NTLMEngineImpl", "RND_GEN"}};
        */

        for (String ignore : ignores) {
            int lastDot = ignore.lastIndexOf(".");
            String clz = ignore.substring(0,lastDot);
            String fld = ignore.substring(lastDot+1);
            try {
                xstream.omitField(Class.forName(clz), fld);
            } catch (Exception ex) {
                //ex.printStackTrace();
                //Do not throw runtime exception, since some modules might indeed not
                //load all classes in the project.
                //throw new RuntimeException(ex);
            }
        }

        return xstream;
    }

    private Document stringToXmlDocument(String str) {
        try {           
            CharArrayReader rdr = new CharArrayReader(str.toCharArray());
            InputSource is = new InputSource(rdr);
            Document doc = dBuilder.parse(is);
            //cleanupDocument(doc);
            return doc;

        } catch(Exception ex) {
            ex.printStackTrace();
            return null;
        }

    }
    
    protected String getFieldFQN(Field f) {
        String clz = f.getDeclaringClass().getName();
        String fld = f.getName();
        return clz + "." + fld;
    }

    public String readFile(String path) throws IOException {
        File file = new File(path);
        return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
    }

    int countFiles(String path) {
        File f = new File(path);
        return f.listFiles().length;
    }

}
