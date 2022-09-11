package com.bennyhuo.kotlin.kcp.deepcopy.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.Scope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.mapTypeParameters
import org.jetbrains.kotlin.ir.expressions.mapValueParameters
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.util.withReferenceScope

@OptIn(ObsoleteDescriptorBasedAPI::class)
class DeepCopyClassTransformer(
    private val pluginContext: IrPluginContext,
    private val pluginAvailability: PluginAvailability
) : IrElementTransformerVoidWithContext(), PluginAvailability by pluginAvailability {

    override fun visitClassNew(declaration: IrClass): IrStatement {
        if (!declaration.isDeepCopyPluginEnabled()) return super.visitClassNew(declaration)

        if (declaration.annotatedAsDeepCopyableDataClass()) {
            declaration.deepCopyFunctionForDataClass()?.let { function ->
                MemberFunctionBuilder(declaration, function, pluginContext).build {
                    declaration.primaryConstructor?.valueParameters?.forEachIndexed { index, valueParameter ->
                        irFunction.valueParameters[index].defaultValue = pluginContext.irFactory.createExpressionBody(
                            irGetProperty(
                                irThis(),
                                declaration.properties.first { it.name == valueParameter.name })
                        )
                    }

                    generateDeepCopyFunctionForDataClass()
                }
            }
        }
        // Only generate implementation for data classes.
        if (declaration.isData && declaration.implementsDeepCopyableInterface()) {
            declaration.deepCopyFunctionForDeepCopyable()?.let { function ->
                MemberFunctionBuilder(declaration, function, pluginContext).build {
                    generateDeepCopyFunctionForDeepCopyable()
                }
            }
        }

        return super.visitClassNew(declaration)
    }
}

@OptIn(ObsoleteDescriptorBasedAPI::class)
private class MemberFunctionBuilder(
    val irClass: IrClass,
    val irFunction: IrFunction,
    val pluginContext: IrPluginContext,
    startOffset: Int = SYNTHETIC_OFFSET,
    endOffset: Int = SYNTHETIC_OFFSET,
) : IrBlockBodyBuilder(pluginContext, Scope(irFunction.symbol), startOffset, endOffset) {

    inline fun <T : IrDeclaration> T.buildWithScope(builder: (T) -> Unit): T =
        also { irDeclaration ->
            pluginContext.symbolTable.withReferenceScope(irDeclaration) {
                builder(irDeclaration)
            }
        }

    inline fun build(builder: MemberFunctionBuilder.(IrFunction) -> Unit) {
        irFunction.buildWithScope {
            builder(irFunction)
            irFunction.body = doBuild()
        }
    }

    fun irThis(): IrExpression {
        val irDispatchReceiverParameter = irFunction.dispatchReceiverParameter!!
        return IrGetValueImpl(
            startOffset, endOffset,
            irDispatchReceiverParameter.type,
            irDispatchReceiverParameter.symbol
        )
    }

    fun transform(typeParameterDescriptor: TypeParameterDescriptor): IrType =
        pluginContext.irBuiltIns.anyType

    fun irGetProperty(receiver: IrExpression, property: IrProperty): IrExpression {
        // In some JVM-specific cases, such as when 'allopen' compiler plugin is applied,
        // data classes and corresponding properties can be non-final.
        // We should use getters for such properties (see KT-41284).
        val backingField = property.backingField
        return if (property.modality == Modality.FINAL && backingField != null) {
            irGetField(receiver, backingField)
        } else {
            irCall(property.getter!!).apply {
                dispatchReceiver = receiver
            }
        }
    }

    fun generateDeepCopyFunctionForDataClass() {
        generateDeepCopyFunction { parameter ->
            val irValueParameter = irFunction.valueParameters[parameter.index]
            generateParameterValue(
                irValueParameter.type.getClass(),
                irGet(irValueParameter.type, irValueParameter.symbol)
            )
        }
    }

    fun generateDeepCopyFunctionForDeepCopyable() {
        generateDeepCopyFunction { parameter ->
            generateParameterValue(
                parameter.type.getClass(),
                irGetProperty(
                    irThis(),
                    irClass.properties.first { it.name == parameter.name }
                )
            )
        }
    }

    private fun generateDeepCopyFunction(
        valueParameterMapper: (irConstructorValueParameter: IrValueParameter) -> IrExpression
    ) {
        val primaryConstructor = irClass.primaryConstructor!!
        +irReturn(
            irCall(
                primaryConstructor.symbol,
                irClass.defaultType,
                constructedClass = irClass
            ).apply {
                mapTypeParameters(::transform)
                mapValueParameters {
                    valueParameterMapper(primaryConstructor.valueParameters[it.index])
                }
            }
        )
    }

    private fun generateParameterValue(irClass: IrClass?, irExpression: IrExpression): IrExpression {
        val possibleCopyFunction = irClass?.deepCopyFunctionForDataClass()
            ?: irClass?.deepCopyFunctionForDeepCopyable()
            ?: irClass?.copyFunctionForDataClass()

        return if (possibleCopyFunction != null) {
            irCall(possibleCopyFunction).apply {
                dispatchReceiver = irExpression
            }
        } else {
            val deepCopyFunctionForCollections = irClass?.deepCopyFunctionForCollections(pluginContext)
            if (deepCopyFunctionForCollections == null) {
                irExpression
            } else {
                irCall(deepCopyFunctionForCollections).apply {
                    extensionReceiver = irExpression
                }
            }
        }
    }

}