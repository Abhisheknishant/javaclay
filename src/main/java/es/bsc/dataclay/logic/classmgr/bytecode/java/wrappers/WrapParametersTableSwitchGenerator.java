
/**
 * 
 */
package es.bsc.dataclay.logic.classmgr.bytecode.java.wrappers;

import java.util.Collection;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.InstructionAdapter;

import es.bsc.dataclay.logic.classmgr.bytecode.java.constants.ByteCodeMethods;
import es.bsc.dataclay.logic.classmgr.bytecode.java.constants.ByteCodeTypes;
import es.bsc.dataclay.util.management.classmgr.MetaClass;
import es.bsc.dataclay.util.management.classmgr.Operation;
import es.bsc.dataclay.util.management.stubs.StubInfo;
import es.bsc.dataclay.util.reflection.Reflector;

/**
 * Run table switch for wrapping parameters.
 */
public final class WrapParametersTableSwitchGenerator extends WrapParametersAndReturnTableSwitchGenerator {

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
	public WrapParametersTableSwitchGenerator(
			final MetaClass themetaClass,
			final StubInfo thestubInfo, final boolean theisExecClass,
			final GeneratorAdapter thegn, final InstructionAdapter theia) {
		super(themetaClass, thestubInfo, theisExecClass, thegn, theia);
	}

	@Override
	protected Collection<es.bsc.dataclay.util.management.classmgr.Type> getTypesToWrap(final Operation operation) {
		return operation.getParams().values();
	}

	@Override
	public void generateCallToParentCase() {
		gn.loadThis();
		gn.loadArg(0);
		gn.loadArg(1);
		gn.visitMethodInsn(Opcodes.INVOKESPECIAL,
				Reflector.getInternalNameFromTypeName(metaClass.getParentType().getTypeName()),
				ByteCodeMethods.WRAP_PARAMETERS.getName(),
				ByteCodeMethods.WRAP_PARAMETERS.getDescriptor(), false);
		gn.returnValue();
	}

	@Override
	protected void getParameter(final int idx) {

		gn.loadArg(1); // Object[]
		ia.aconst(idx); // Stack: <This> <Array> <Idx>
		gn.arrayLoad(ByteCodeTypes.OBJECT);
	}

	@Override
	protected boolean checkIfNothingToSerialize(final Operation operation) {
		return false;
	}

}
