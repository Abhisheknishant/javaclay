
/**
 * 
 */
package es.bsc.dataclay.logic.classmgr.bytecode.java.wrappers;

import java.util.ArrayList;
import java.util.Collection;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.InstructionAdapter;

import es.bsc.dataclay.logic.classmgr.bytecode.java.constants.ByteCodeMethods;
import es.bsc.dataclay.util.management.classmgr.MetaClass;
import es.bsc.dataclay.util.management.classmgr.Operation;
import es.bsc.dataclay.util.management.stubs.StubInfo;
import es.bsc.dataclay.util.reflection.Reflector;

/**
 * Run table switch for wrapping parameters.
 */
public final class WrapReturnTableSwitchGenerator extends WrapParametersAndReturnTableSwitchGenerator {

	/**
	 * Constructor
	 * @param themetaClass
	 *            Information of class.
	 * @param thestubInfo
	 *            Stub info
	 * @param theisExecClass
	 *            isExec class
	 * @param thegn
	 *            Method generator.
	 * @param theia
	 *            Instruction adapter
	 */
	public WrapReturnTableSwitchGenerator(
			final MetaClass themetaClass,
			final StubInfo thestubInfo, final boolean theisExecClass,
			final GeneratorAdapter thegn, final InstructionAdapter theia) {
		super(themetaClass, thestubInfo, theisExecClass, thegn, theia);
	}

	@Override
	protected Collection<es.bsc.dataclay.util.management.classmgr.Type> getTypesToWrap(final Operation operation) {
		final ArrayList<es.bsc.dataclay.util.management.classmgr.Type> types = new ArrayList<>();
		types.add(operation.getReturnType());
		return types;
	}

	@Override
	public void generateCallToParentCase() {
		gn.loadThis();
		gn.loadArg(0);
		gn.loadArg(1);
		gn.visitMethodInsn(Opcodes.INVOKESPECIAL,
				Reflector.getInternalNameFromTypeName(metaClass.getParentType().getTypeName()),
				ByteCodeMethods.WRAP_RETURN.getName(),
				ByteCodeMethods.WRAP_RETURN.getDescriptor(), false);
		gn.returnValue();
	}

	@Override
	protected void getParameter(final int idx) {
		gn.loadArg(1);
	}

	@Override
	protected boolean checkIfNothingToSerialize(final Operation operation) {
		if (operation.getReturnType().getTypeName().equals("void")) {
			ia.aconst(null);
			gn.returnValue();
			return true;
		}
		return false;
	}

}
