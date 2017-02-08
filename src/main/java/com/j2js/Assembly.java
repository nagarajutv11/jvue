package com.j2js;

import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.IOUtils;

import com.j2js.assembly.ClassUnit;
import com.j2js.assembly.ConstructorUnit;
import com.j2js.assembly.JavaScriptCompressor;
import com.j2js.assembly.JunkWriter;
import com.j2js.assembly.MemberUnit;
import com.j2js.assembly.ProcedureUnit;
import com.j2js.assembly.Project;
import com.j2js.assembly.Signature;

/**
 * 
 */
public class Assembly {

	private transient J2JSCompiler compiler;

	public List<String> entryPoints = new ArrayList<String>();

	private final static String DECLARECLASS = "dcC";

	private transient Log logger;

	private transient String entryPointClassName;

	private Project project;

	private Set<Signature> taintedSignatures = new HashSet<Signature>();

	private Set<Signature> unprocessedTaintedSignatures = new HashSet<Signature>();

	String[] patterns;

	private Collection<ClassUnit> resolvedTypes = new ArrayList<ClassUnit>();

	private transient File targetLocation;

	public Assembly(J2JSCompiler compiler) {
		this.compiler = compiler;
		patterns = Utils.getProperty("j2js.taintIfInstantiated").split(";");
		for (int i = 0; i < patterns.length; i++) {
			// Remove all whitespace, quote '.', '(', ')', and transform
			// '*' to '.*'.
			String pattern = patterns[i].replaceAll("\\s", "");
			pattern = pattern.replaceAll("\\.", "\\\\.");
			pattern = pattern.replaceAll("\\*", ".*");
			pattern = pattern.replaceAll("\\(", "\\\\(");
			pattern = pattern.replaceAll("\\)", "\\\\)");
			patterns[i] = pattern;
		}
	}

	/**
	 * Pipes a file on the class path into a stream.
	 * 
	 * @param writer
	 *            the sream into which to pipe
	 * @param classPath
	 *            the array of directories in which to locate the file
	 * @param relativeFilePath
	 *            the name of the file
	 * 
	 * @throws IOException
	 */
	private void pipeFileToStream(Writer writer, String relativeFilePath) throws IOException {
		FileObject fileObject = compiler.fileManager.getFileForInput(relativeFilePath);
		String content;
		if (J2JSSettings.compression) {
			JavaScriptCompressor compressor = new JavaScriptCompressor();
			content = compressor.compress(fileObject.openInputStream());
		} else {
			content = IOUtils.toString(fileObject.openInputStream());
		}
		writer.write(content);
	}

	private void removeOldAssemblies(File assembly) {
		final String numericPostfixPattern = "-[0-9]*$";
		final String prefix = assembly.getName().replaceAll(numericPostfixPattern, "");

		File[] oldAssemblies = assembly.getParentFile().listFiles(new FilenameFilter() {
			public boolean accept(File dir1, String name) {
				return name.matches(prefix + numericPostfixPattern);
			}
		});

		if (oldAssemblies == null) {
			return;
		}

		for (File oldAssemblyDir : oldAssemblies) {
			for (File file : oldAssemblyDir.listFiles()) {
				file.delete();
			}
			oldAssemblyDir.delete();
		}
	}

	public int createAssembly() throws IOException {
		logger = Log.getLogger();
		logger.debug("Packing ...");

		String loaderName = compiler.getTargetPlatform().toLowerCase();
		Writer writer;
		if (compiler.writer != null) {
			writer = compiler.writer;
		} else {
			removeOldAssemblies(targetLocation);
			if ("javascript".equals(loaderName)) {
				writer = new FileWriter(targetLocation);
				pipeFileToStream(writer, "javascript/loaders/" + loaderName + ".js");
			} else {
				targetLocation.mkdirs();
				writer = new JunkWriter(targetLocation, compiler);
			}
		}
		writer.write("// Assembly generated by j2js " + Utils.getVersion() + " on " + Utils.currentTimeStamp() + "\n");
		pipeFileToStream(writer, "javascript/runtime.js");
		writer.write("j2js.assemblyVersion = 'j2js Assembly " + (targetLocation != null ? targetLocation.getName() : "")
				+ "@" + Utils.currentTimeStamp() + "';\n");

		writer.write("j2js.userData = {};\n");

		int classCount = 0;
		for (ClassUnit fileUnit : project.getClasses()) {
			if (!fileUnit.isTainted())
				continue;
			writer.write("j2js.");
			writer.write(DECLARECLASS);
			writer.write("(\"" + fileUnit.getSignature() + "\"");
			writer.write(", " + fileUnit.getSignature().getId());
			writer.write(");\n");
			classCount++;
		}

		project.currentGeneratedMethods = 0;

		if (compiler.getSingleEntryPoint() != null) {
			Signature signature = project.getSignature(compiler.getSingleEntryPoint());
			ClassUnit clazz = project.getClassUnit(signature.className());
			clazz.write(0, writer);
		} else {
			ClassUnit object = project.getJavaLangObject();
			object.write(0, writer);

			for (ClassUnit cu : project.getClasses()) {
				// TODO Interface: Generate them nicely.
				if (cu.isInterface) {
					cu.write(0, writer);
				}
			}

			// for (ClassUnit cu : project.getClasses()) {
			// if (cu.isInterface && cu.getInterfaces().size() != 0) {
			// cu.write(0, writer);
			// }
			// }
		}

		if (getProject().getOrCreateClassUnit("java.lang.String").isTainted()) {
			writer.write("String.prototype.clazz = j2js.forName('java.lang.String');\n");
		}

		writer.write("j2js.onLoad('" + entryPointClassName + "#main(java.lang.String[])void');\n");

		// String resourcePath = J2JSCompiler.compiler.resourcePath;
		// if (resourcePath != null) {
		// resolveResources(new File(J2JSCompiler.compiler.getBaseDir(),
		// resourcePath), writer);
		// }

		writer.close();

		if ("web".equals(loaderName)) {
			Writer loader = new FileWriter(new File(targetLocation, "/0.js"));
			loader.write("var j2js = {assemblySize:");
			loader.write(Integer.toString(((JunkWriter) writer).getSize()));
			loader.write("};\n");
			pipeFileToStream(loader, "javascript/loaders/" + loaderName + ".js");
			loader.close();
		}

		return project.currentGeneratedMethods;
	}

	// private void resolveResources(File resourcePath, Writer writer) throws
	// FileNotFoundException, IOException {
	// File cssPath = new File(resourcePath, "/style.css");
	// BufferedReader reader = new BufferedReader(new FileReader(cssPath));
	// StringBuffer buffer = new StringBuffer();
	//
	// do {
	// String line = reader.readLine();
	// if (line == null) break;
	// buffer.append(line.trim());
	// buffer.append(" ");
	// } while (true);
	//
	// reader.close();
	//
	// writer.write("j2js.style = \"" + buffer.toString() + "\";\n");
	// }

	// private void writeDependency() {
	// Iterator iter = taintedSignatures.iterator();
	// while (iter.hasNext()) {
	// Signature signature = (Signature) iter.next();
	// logger.fine(signature + " <- " + signature.referer);
	// }
	// }

	public void processTainted() {
		while (unprocessedTaintedSignatures.size() > 0) {
			processSingle(popSignature());
			if (unprocessedTaintedSignatures.size() == 0) {
				processOverWrittenMembers();
			}
		}
	}

	public void processSingle(Signature signature) {
		ClassUnit clazz = resolve(signature.className());
		String methodPart = signature.relativeSignature();
		boolean found = false;
		for (MemberUnit member : clazz.getMembers(methodPart)) {
			taint(member);
			found = true;
		}

		if (!found) {
			// logger.severe("No such method: " + signature);
			// throw new RuntimeException("No such method: " + signature);
		}

		// MemberUnit member = clazz.getMember(methodPart);
		// if (member != null) {
		// taint(member);
		// } else {
		// logger.severe("No such method: " + signature);
		// }
	}

	private ClassUnit resolve(String className) {
		ClassUnit clazz = project.getOrCreateClassUnit(className);

		if (className.startsWith("[")) {
			project.resolve(clazz);
		} else {
			project.resolve(clazz);
			taint(className + "#<clinit>()void");
		}

		resolvedTypes.add(clazz);

		return clazz;
	}

	/**
	 * Taint the signatures of methods matching a pattern.
	 */
	private void taintImplicitelyAccessedMembers(ClassUnit clazz) {
		// TODO: This method is called multiple times for each clazz. This
		// should not be the case!
		// System.out.println(clazz.toString());
		for (MemberUnit member : clazz.getDeclaredMembers()) {
			for (int i = 0; i < patterns.length; i++) {
				if (member.getAbsoluteSignature().toString().matches(patterns[i])) {
					taint(member.getAbsoluteSignature());
				}
			}
		}
	}

	/**
	 * Taints all members which overwrite another tainted member.
	 */
	private void taintIfSuperTainted(ClassUnit clazz) {
		if (clazz.getName().equals("java.lang.Object"))
			return;

		for (MemberUnit member : clazz.getDeclaredMembers()) {
			for (ClassUnit superType : clazz.getSupertypes()) {
				Signature signature = Project.getSingleton().getSignature(superType.getName(),
						member.getSignature().toString());
				if (taintedSignatures.contains(signature)) {
					taint(member);
				}
			}
		}
	}

	/**
	 * Taint all signatures targeted by the specified method.
	 */
	private void taintTargetSignatures(ProcedureUnit method) {
		for (Signature targetSignature : method.getTargetSignatures()) {
			taint(targetSignature);
		}
	}

	private void processOverWrittenMembers() {
		Iterator<ClassUnit> classIterator = resolvedTypes.iterator();
		while (classIterator.hasNext()) {
			ClassUnit clazz = classIterator.next();
			if (clazz.isConstructorTainted)
				taintIfSuperTainted(clazz);
			;
		}
	}

	public void taint(String signature) {
		signature = signature.replaceAll("\\s", "");
		// if (signature.indexOf('*') != -1) {
		// if (!signature.endsWith(".*")) {
		// throw new RuntimeException("Package signature must end with '.*'");
		// }
		// signature = signature.substring(0, signature.length()-2);
		// File[] files =
		// Utils.resolvePackage(J2JSCompiler.compiler.getClassPath(),
		// signature);
		// for (int i=0; i<files.length; i++) {
		// String name = files[i].getName();
		// if (name.endsWith(".class")) {
		// taint(signature + '.' + name.substring(0, name.length()-6));
		// }
		// }
		// } else {
		Signature s = Project.getSingleton().getSignature(signature);
		if (s.isClass()) {
			ClassUnit clazz = resolve(s.className());
			for (MemberUnit member : clazz.getDeclaredMembers()) {
				taint(member.getAbsoluteSignature());
			}
		} else {
			taint(s);
		}
		// }
	}

	private Signature popSignature() {
		Iterator<Signature> iter = unprocessedTaintedSignatures.iterator();
		Signature signature = iter.next();
		iter.remove();
		return signature;
	}

	public void taint(Signature signature) {
		if (!signature.toString().contains("#")) {
			throw new IllegalArgumentException("Signature must be field or method: " + signature);
		}

		if (taintedSignatures.contains(signature))
			return;

		taintedSignatures.add(signature);
		unprocessedTaintedSignatures.add(signature);
	}

	public void taint(MemberUnit member) {
		member.setTainted();
		// TODO: Tainting super types in neccesary for generating only?
		member.getDeclaringClass().setSuperTainted();
		if (member instanceof ProcedureUnit) {
			taintTargetSignatures((ProcedureUnit) member);
			if (member instanceof ConstructorUnit) {
				member.getDeclaringClass().isConstructorTainted = true;
				taintImplicitelyAccessedMembers(member.getDeclaringClass());
			}
		}
	}

	/**
	 * @see #getProject()
	 */
	public void setProject(Project project) {
		this.project = project;
	}

	public Project getProject() {
		return project;
	}

	/**
	 * @return Returns the entryPointClassName.
	 */
	public String getEntryPointClassName() {
		return entryPointClassName;
	}

	/**
	 * <p>
	 * The static <code>main(String[])void</code> method of the specified class
	 * is executed after the assembly is loaded by the script engine.
	 * </p>
	 * <p>
	 * In web mode, the values of the URL query string are passed to this
	 * method.
	 * </p>
	 * 
	 * @param entryPointClassName
	 *            (required) the class signature
	 */
	public Assembly setEntryPointClassName(String entryPointClassName) {
		this.entryPointClassName = entryPointClassName;
		return this;
	}

	/**
	 * @return Returns the targetLocation.
	 */
	public File getTargetLocation() {
		return targetLocation;
	}

	/**
	 * The assembly is written to the specified directory
	 * 
	 * @param targetLocation
	 *            (required) the directory the assembly is written to
	 */
	public void setTargetLocation(File targetLocation) {
		this.targetLocation = targetLocation;
	}

	/**
	 * Signature of a field or method to include in the assembly.
	 * <p>
	 * <strong>Note</strong>: Normally you will only add those entrypoints which
	 * the static code analyser cannot detect, for example methods called
	 * through reflection.
	 * </p>
	 * 
	 * @param memberSignature
	 *            the member to include
	 */
	public void addEntryPoint(String memberSignature) {
		entryPoints.add(memberSignature);
	}

}
