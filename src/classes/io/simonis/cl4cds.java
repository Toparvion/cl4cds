/*
 * Copyright (c) 2017, Volker Simonis
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.simonis;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * @author simonis
 *
 */
public class cl4cds {

  private static final String  FatJarTmp = System.getProperty("io.simonis.cl4cds.fatJarTmp", "./tmp");
  private static final boolean DBG = Boolean.getBoolean("io.simonis.cl4cds.debug");
  private static final boolean DumpFromClassFiles = Boolean.getBoolean("io.simonis.cl4cds.dumpFromClassFile");
  private static final boolean CompactIDs = Boolean.parseBoolean(System.getProperty("io.simonis.cl4cds.compactIDs", "true"));
  private static final boolean ClassesOnly = Boolean.parseBoolean(System.getProperty("io.simonis.cl4cds.classesOnly", "false"));
  
  private enum Status {
    OK, ERROR, PRE_15, LOAD_ERROR, ZIP_ERROR, JAR_ERROR
  }

  public static void main(String... args) {
    BufferedReader in = null;
    PrintStream out = null;
    if (args.length == 0) {
      in = new BufferedReader(new InputStreamReader(System.in));
      out = System.out;
    }
    else if (args.length == 1) {
      if ("-h".equals(args[0]) ||
          "--help".equals(args[0]) ||
          "/?".equals(args[0])) {
        help(0);
      }

      try {
        in = Files.newBufferedReader(Paths.get(args[0]));
      } catch (IOException e) {
        error("Cant open \"" + args[0] + "\" for reading!");
      }
      out = System.out;
    }
    else if (args.length == 2) {
      try {
        in = Files.newBufferedReader(Paths.get(args[0]));
      } catch (IOException e) {
        error("Cant open \"" + args[0] + "\" for reading!");
      }
      try {
        out = new PrintStream(args[1]);
      } catch (IOException e) {
        error("Cant open \"" + args[0] + "\" for writing!");
      }
    }

    convert(in, out);
  }

  private static void convert(BufferedReader in, PrintStream out) {
    // Pattern for JVM class names (see JVMLS §4.2)
    final String uqNameP = "((?:[^,;/\\[]+?\\.)*(?:[^,;/\\[]+?))";
    final String timeDecoP = "\\[.+?\\]";
    final String hexP = " (0x[0-9a-f]+)";
    final String hexesP = " (0x[0-9a-f]+(?: 0x[0-9a-f]+)*)";
    final String infoDecoP = "\\[info *\\Q][class,load]\\E ";
    final String debugDecoP = "\\[debug *\\Q][class,load]\\E ";
    Pattern firstLineP = Pattern.compile(timeDecoP + infoDecoP + uqNameP + " source: (.+)");
    Pattern secondLineP = Pattern.compile(timeDecoP + debugDecoP + 
        " klass:" + hexP + " super:" + hexP + "(?: interfaces:" + hexesP + ")? loader: \\[(.+?)\\]" + ".+");
    if (DBG) {
      System.err.println("The following two patterns are used to match the -Xlog:class+load=trace output:");
      System.err.println("  " + firstLineP.toString());
      System.err.println("  " + secondLineP.toString());
    }
    Matcher firstLine = firstLineP.matcher("");
    Matcher secondLine = secondLineP.matcher("");
    try (in) {
      String line;
      long objectID = 0;
      Set<String> klassSet = new HashSet<>();
      Set<String> klassNameSet = new HashSet<>();
      while((line = in.readLine()) != null) {
        if (firstLine.reset(line).matches()) {
          MatchResult mr1 = firstLine.toMatchResult();
          String name = mr1.group(1);
          String source = mr1.group(2);
          if (source.contains("__JVM_DefineClass__")) {
            // skip classes which have been generated dynamically at runtime
            System.err.println("Skipping " + name + " from " + source + " - reason: dynamically generated class");
            continue;
          }
          if ((line = in.readLine()) != null &&
              secondLine.reset(line).matches()) {
            MatchResult mr2 = secondLine.toMatchResult();
            String klass = mr2.group(1);
            String parent = mr2.group(2);
            String interf = mr2.group(3);
            String loader = mr2.group(4);

            if (CompactIDs && "java.lang.Object".equals(name)) {
              if (objectID != 0) {
                System.err.println("java.lang.Object can't be loaded twice");
                System.exit(-1);
              }
              objectID = Long.parseUnsignedLong(klass.substring(2), 16) - 1;
              if (DBG) {
                System.err.println("java.lang.Objekt klass = " + klass + " (" + objectID +")");
              }
            }

            if (CompactIDs) {
              klass = compact(klass, objectID);
              parent = compact(parent, objectID);
              if (interf != null) {
                interf = compact(interf, objectID);
              }
            }

            if (DBG) {
              System.err.println("loader = " + loader);
            }

            if ("NULL class loader".equals(loader) ||
                loader.contains("of <bootloader>") || // this is JDK 11 syntax
                loader.contains("of 'bootstrap'") || // this is JDK 12 syntax
                loader.contains("jdk/internal/loader/ClassLoaders$PlatformClassLoader" /* && source == jrt image */) ||
                loader.contains("jdk/internal/loader/ClassLoaders$AppClassLoader" /* && source == jar file */)) {
              out.print(name.replace('.', '/'));
              if (!ClassesOnly) {
                out.print(" id: " + klass);
              }
              out.println();
              klassSet.add(klass);
              klassNameSet.add(name);
            }
            else {
              // Custom class loader (currently only supported if classes are loaded from jar files ?)
              String sourceFile = null;
              if (source != null && source.startsWith("file:") /* && source.endsWith(".jar") */) {
                sourceFile = source.substring("file:".length());
              }
              else if (source != null && source.startsWith("jar:file:") && source.endsWith("!/")) {
                sourceFile = source.substring("jar:file:".length(), source.length() - 2);
              }
              else {
                System.err.println("Skipping " + name + " from " + source + " - reason: unknown source format");
                continue;
              }
              if (!DumpFromClassFiles)
                if (sourceFile.startsWith("/") && sourceFile.charAt(2) == ':') {
                  sourceFile = sourceFile.substring(1);   // to remove leading slash from paths like '/C:/Users/...'
                }
                if (Files.isDirectory(Paths.get(sourceFile))) {
                  System.err.println("Skipping " + name + " from " + sourceFile + " - reason: loaded from class file (try '-Dio.simonis.cl4cds.dumpFromClassFile=true')");
                  continue;
                }
              Status ret;

              if (sourceFile.endsWith(".jar") && sourceFile.contains(".jar!")) {
                // This is a fat jar file, so let's extract it
                sourceFile = extractFatJar(sourceFile);
              }
              if ((ret = checkClass(name.replace('.', '/'), sourceFile)) != Status.OK) {
                switch (ret) {
                case PRE_15 : 
                  System.err.println("Skipping " + name + " from " + sourceFile + " - reason: class is pre 1.5");
                  break;
                case LOAD_ERROR:
                case ZIP_ERROR:
                case JAR_ERROR:
                  System.err.println("Skipping " + name + " from " + sourceFile + " - reason: can't load (maybe generataed?))");
                  break;
                case ERROR:
                  System.err.println("Skipping " + name + " from " + sourceFile + " - reason: unknown source");
                  break;
                }
                continue;
              }
              if (klassNameSet.contains(name)) {
                System.err.println("Skipping " + name + " from " + sourceFile + " - reason: already dumped");
                continue;
              }
              List<String> deps = new LinkedList<>();
              deps.add(parent);
              if (interf != null) {
                deps.addAll(Arrays.asList(interf.split("\\s")));
              }
              if (klassSet.containsAll(deps)) {
                out.print(name.replace('.', '/'));
                if (!ClassesOnly) {
                  out.print(" id: " + klass + " super: " + parent);
                  if (interf != null) {
                    out.print(" interfaces: " + interf);
                  }
                  out.print(" source: " + sourceFile);
                }
                out.println();
                klassSet.add(klass);
                klassNameSet.add(name);
              }
              else {
                System.err.println("Skipping " + name + " from " + sourceFile + " - reason: failed dependencies");
              }
            }
          }
        }
      }
    }
    catch (IOException ioe) {
      System.err.println("Error reading input file:\n" + ioe);
    }

  }

  static HashMap<String, String> fatJarCache = new HashMap<>();

  private static void mkdir(File dir) {
    if (dir.isFile()) {
      System.err.println("Error: " + dir + " is not a directory!");
    }
    if (!dir.exists()) {
      try {
        Files.createDirectories(dir.toPath());
      } catch (IOException ioe) {
        System.err.println("Can't create temporary directory " + dir + "(" + ioe + ")");
      }
      if (DBG) {
        System.err.println("Created " + dir + " to extract nested JARs");
      }
    }
  }

  private static String extractFatJar(String source) {
    String cache = fatJarCache.get(source);
    if (cache != null) {
      return cache;
    }

    int index = source.indexOf('!');
    String mainJar = source.substring(0, index);
    String childJar = source.substring(index+2);
    String tmpFile = FatJarTmp + "/" + childJar;

    if (Files.isRegularFile(Paths.get(mainJar))) {
      try (JarFile jar = new JarFile(mainJar)) {
        ZipEntry ze = jar.getEntry(childJar);
        if (ze != null) {
          try (InputStream in = jar.getInputStream(ze)) {
            File dir = (new File(tmpFile)).getParentFile();
            mkdir(dir);
            try (FileOutputStream out = new FileOutputStream(tmpFile)) {
              byte buff[] = new byte[4096];
              int n;
              while ((n = in.read(buff)) > 0) {
                out.write(buff, 0, n);
              }
            }
            if (DBG) {
              System.out.println("Extracted " + childJar + " from " + mainJar + " into " + dir);
            }
          }
          fatJarCache.put(source, tmpFile);
          return tmpFile;
        }
      } catch (IOException e) {
        if (DBG) {
          System.err.println("Can't extract " + childJar + " from " + mainJar + "(" + e + ")");
          return source;
        }
      }
    }
    return source;
  }

  private static Status checkClass(String name, String source) {
    if (Files.isDirectory(Paths.get(source))) {
      try (InputStream in = new FileInputStream(source + name + ".class")) {
        if (classVersion(in) < 49) return Status.PRE_15;
        return Status.OK;
      } catch (IOException e) {
        if (DBG) {
          System.err.println("Can't check class " + name + " from " + source + "\n" + e);
          return Status.LOAD_ERROR;
        }
      }
    }
    else if (source.endsWith(".jar") && Files.isRegularFile(Paths.get(source))) {
      try (JarFile jar = new JarFile(source)) {
        ZipEntry ze = jar.getEntry(name + ".class");
        if (ze != null) {
          if (classVersion(jar.getInputStream(ze)) < 49) return Status.PRE_15;
          return Status.OK;
        }
        else if (DBG) {
          System.err.println("Can't get zip entry " + name + ".class" + " from jar file " + source);
          return Status.ZIP_ERROR;
        }
      } catch (IOException e) {
        if (DBG) {
          System.err.println("Can't check class " + name + " from jar file " + source + "\n" + e);
          return Status.JAR_ERROR;
        }
      }
    }
    if (DBG) {
      System.err.println("Can't check " + name + " from " + source);
    }
    return Status.ERROR;
  }

  private static int classVersion(InputStream in) throws IOException {
    try (DataInputStream dis = new DataInputStream(in)) {
      int magic = dis.readInt();
      if (magic != 0xcafebabe) {
        if (DBG) {
          System.err.println("Invalid class file!");
          return 0;
        }
      }
      int minor = dis.readUnsignedShort();
      int major = dis.readUnsignedShort();
      return major;
    }
  }

  private static String compact(String ids, long objectID) {
    String compact = "";
    Scanner scan = new Scanner(ids);
    while (scan.hasNext()) {
      long id = Long.parseLong(scan.next().substring(2), 16);
      int compactID = (id == 0) ? 0 : (int)(id - objectID);
      if (compactID < 0) {
        System.err.println("Negative klass ID (" + ids + ", " + compactID + "). Try: -Dio.simonis.cl4cds.compactIDs=false");
      }
      compact += ("".equals(compact) ? "" : " ") + compactID;
    }
    if (DBG) {
      System.err.println("Compacting " + ids + " to " + compact);
    }
    return compact;
  }

  private static void error(String msg) {
    System.err.println(msg);
    help(-1);
  }

  private static void help(int status) {
    System.out.println();
    System.out.println("io.simonis.cl4cds [<class-trace-file> [<class-list-file>]]");
    System.out.println();
    System.out.println("  <class-trace-file>: class trace output obtained by running -Xlog:class+load");
    System.out.println("                      if not specified read from <stdin>");
    System.out.println("  <class-list-file> : class list which can be passed to -XX:SharedClassListFile");
    System.out.println("                      if not specified written to <stdout>");
    System.out.println();
    System.out.println("  The following properties can be used to configure cl4cds:");
    System.out.println("    -Dio.simonis.cl4cds.compactIDs=true :");
    System.out.println("       Substract the address of java.lang.Object's 'klass' from all 'klass',");
    System.out.println("       'super' and 'interface' IDs. This should lead to smaller IDs which ");
    System.out.println("       don't overflow if stored in a 32-bit integer (defaults to 'true')");
    System.out.println("    -Dio.simonis.cl4cds.debug=true :");
    System.out.println("       Print additional tracig to <stderr> (defaults to 'false')");
    System.out.println("    -Dio.simonis.cl4cds.dumpFromClassFile=true :");
    System.out.println("       Include classes into the output which are loaded from plain classfiles.");
    System.out.println("       This is currently not supported by OpenJDK 10 which can only dump");
    System.out.println("       classes from .jar files but may change eventually (defaults to 'false')");
    System.out.println("    -Dio.simonis.cl4cds.fatJarTmp=<directory> :");
    System.out.println("       If application classes are loaded from a \"fat\" JAR file (i.e. a JAR");
    System.out.println("       which contains other, nested JAR files), these nested JAR files will be");
    System.out.println("       extracted to <directory> (defaults to './tmp')");
    System.out.println("    -Dio.simonis.cl4cds.classesOnly=false :");
    System.out.println("       Inlcude pure class FQ names into output (without IDs, super, interface, etc)");
    System.out.println();
    System.exit(status);
  }

}
