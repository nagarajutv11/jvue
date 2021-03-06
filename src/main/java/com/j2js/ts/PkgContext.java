package com.j2js.ts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.apache.bcel.generic.ObjectType;

import com.j2js.dom.TypeDeclaration;
import com.j2js.ext.ExtInvoker;

public class PkgContext {

	private Map<String, TypeContext> clss = new HashMap<>();
	private List<String> orderedClasses = new ArrayList<>();
	private Set<String> generatedClasses = new HashSet<>();
	private J2TSCompiler compiler;

	public PkgContext(J2TSCompiler compiler) {
		this.compiler = compiler;
	}

	public TypeContext get(TypeDeclaration type) {
		String name = type.getClassName();
		return getType(type, name);
	}

	private TypeContext getType(TypeDeclaration type, String name) {
		String[] split = name.split("\\$");
		if (split.length == 1) {
			TypeContext cls = clss.get(split[0]);
			if (cls == null) {
				cls = new TypeContext(type);
				clss.put(split[0], cls);
			}
			addToOrderedClasses(type);
			return cls;
		} else {
			TypeContext s = clss.get(split[0]);
			if (s == null) {
				compiler.addClass(split[0], true);
				s = clss.get(split[0]);
				if (s == null) {
					System.err.println("Need to skip this class " + name);
				}
			}
			for (int i = 1; i < split.length - 1; i++) {
				s = s.getAnonymous(split[i]);
				if (s == null) {
					System.err.println("Need to skip this class " + name);
				}
			}
			TypeContext cls = s.getInnerClass(split[split.length - 1], type);
			return cls;
		}
	}

	private void addToOrderedClasses(TypeDeclaration type) {
		String className = type.getClassName();
		generatedClasses.add(className);
		ObjectType superType = type.getSuperType();
		if (superType != null) {
			if (orderedClasses.contains(superType.getClassName())) {
				if (orderedClasses.contains(className)) {
					int ci = orderedClasses.indexOf(className);
					int si = orderedClasses.indexOf(superType.getClassName());
					if (si > ci) {
						orderedClasses.remove(superType.getClassName());
						orderedClasses.add(ci, superType.getClassName());
					}
				} else {
					orderedClasses.add(className);
				}
			} else {
				if (orderedClasses.contains(className)) {
					orderedClasses.add(orderedClasses.indexOf(className), superType.getClassName());
				} else {
					orderedClasses.add(superType.getClassName());
					orderedClasses.add(className);
				}
			}
		} else {
			if (!orderedClasses.contains(className)) {
				orderedClasses.add(className);
			}
		}
	}

	public void write(ExtInvoker inv, File base) {

		foreachOrdeby((s, st) -> {
			File file = new File(base, s + "." + compiler.settings.ext);
			try {
				PrintStream single = new PrintStream(new FileOutputStream(file));
				inv.invoke("file.create", single, null);
				st.write(inv, single);
				inv.invoke("file.end", single, null);
				single.flush();
				single.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	public void write(ExtInvoker inv, PrintStream ps) {
		// Set<String> totalImports = clss.values().stream().map(c ->
		// c.getImports()).flatMap(is -> is.stream()).distinct()
		// .filter(i ->
		// !generatedClasses.contains(i)).collect(Collectors.toSet());
		// ExtRegistry.get().invoke("imports", ps, totalImports);

		foreachOrdeby((s, st) -> {
			try {
				ps.println();
				st.write(inv, ps);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	private void foreachOrdeby(BiConsumer<String, TypeContext> action) {
		orderedClasses.forEach(c -> {
			TypeContext t = clss.get(c);
			if (t != null) {
				action.accept(c, t);
			}

		});

	}
}
