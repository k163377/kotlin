/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.parcelize

import org.jetbrains.kotlin.ir.util.erasedUpperBound
import org.jetbrains.kotlin.backend.jvm.ir.isJvmInterface
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.util.isBoxedArray
import org.jetbrains.kotlin.name.SpecialNames

interface IrParcelSerializer {
    fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression
    fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression
}

fun AndroidIrBuilder.readParcelWith(serializer: IrParcelSerializer, parcel: IrValueDeclaration): IrExpression {
    return with(serializer) { readParcel(parcel) }
}

fun AndroidIrBuilder.writeParcelWith(
    serializer: IrParcelSerializer,
    parcel: IrValueDeclaration,
    flags: IrValueDeclaration,
    value: IrExpression
): IrExpression {
    return with(serializer) { writeParcel(parcel, flags, value) }
}

fun IrParcelSerializer.withDeserializationPostprocessing(mapper: IrSimpleFunctionSymbol): IrParcelSerializer =
    object : IrParcelSerializer by this {
        override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
            val readResult = with(this@withDeserializationPostprocessing) { readParcel(parcel) }
            return irCall(mapper).apply { arguments[0] = readResult }
        }
    }

// Creates a serializer from a pair of parcel methods of the form reader()T and writer(T)V.
class IrSimpleParcelSerializer(private val reader: IrSimpleFunctionSymbol, private val writer: IrSimpleFunctionSymbol) :
    IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return irCall(reader).apply { arguments[0] = irGet(parcel) }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return irCall(writer).apply {
            arguments[0] = irGet(parcel)
            arguments[1] = value
        }
    }
}

// Serialize a value of the primitive [parcelType] by coercion to int.
class IrWrappedIntParcelSerializer(private val parcelType: IrType) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return if (parcelType.isBoolean()) {
            irNotEquals(parcelReadInt(irGet(parcel)), irInt(0))
        } else {
            val conversion = context.irBuiltIns.intClass.functions.first { function ->
                function.owner.name.asString() == "to${parcelType.getClass()!!.name}"
            }
            irCall(conversion).apply {
                dispatchReceiver = parcelReadInt(irGet(parcel))
            }
        }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
        parcelWriteInt(
            irGet(parcel),
            if (parcelType.isBoolean()) {
                irIfThenElse(context.irBuiltIns.intType, value, irInt(1), irInt(0))
            } else {
                val conversion = parcelType.classOrNull!!.functions.first { function ->
                    function.owner.name.asString() == "toInt"
                }
                irCall(conversion).apply { dispatchReceiver = value }
            }
        )
}

class IrUnsafeCoerceWrappedSerializer(
    private val serializer: IrParcelSerializer,
    private val wrappedType: IrType,
    private val underlyingType: IrType,
) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return unsafeCoerce(readParcelWith(serializer, parcel), underlyingType, wrappedType)
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return writeParcelWith(serializer, parcel, flags, unsafeCoerce(value, wrappedType, underlyingType))
    }
}

// Wraps a non-null aware parceler to handle nullable types.
class IrNullAwareParcelSerializer(private val serializer: IrParcelSerializer) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        val nonNullResult = readParcelWith(serializer, parcel)
        return irIfThenElse(
            nonNullResult.type.makeNullable(),
            irEquals(parcelReadInt(irGet(parcel)), irInt(0)),
            irNull(),
            nonNullResult
        )
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return irLetS(value) { irValueSymbol ->
            irIfNull(
                context.irBuiltIns.unitType,
                irGet(irValueSymbol.owner),
                parcelWriteInt(irGet(parcel), irInt(0)),
                irBlock {
                    +parcelWriteInt(irGet(parcel), irInt(1))
                    +writeParcelWith(serializer, parcel, flags, irGet(irValueSymbol.owner))
                }
            )
        }
    }
}

// Parcel serializer for object classes. We avoid empty parcels by writing a dummy value. Not null-safe.
class IrObjectParcelSerializer(private val objectClass: IrClass) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression
    // Avoid empty parcels
    {
        return irBlock {
            +parcelReadInt(irGet(parcel))
            +irGetObject(objectClass.symbol)
        }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return parcelWriteInt(irGet(parcel), irInt(1))
    }
}

// Parcel serializer for classes with a default constructor. We avoid empty parcels by writing a dummy value. Not null-safe.
class IrNoParameterClassParcelSerializer(private val irClass: IrClass) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        val defaultConstructor = irClass.primaryConstructor!!
        return irBlock {
            +parcelReadInt(irGet(parcel))
            +irCall(defaultConstructor)
        }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return parcelWriteInt(irGet(parcel), irInt(1))
    }
}

// Parcel serializer for enum classes. Not null-safe.
class IrEnumParcelSerializer(enumClass: IrClass) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return irCall(enumValueOf).apply {
            arguments[0] = parcelReadString(irGet(parcel))
        }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return parcelWriteString(irGet(parcel), irCall(enumName).apply {
            arguments[0] = value
        })
    }

    private val enumValueOf: IrFunctionSymbol =
        enumClass.functions.single { function ->
            function.name.asString() == "valueOf" && function.parameters.singleOrNull()?.type?.isString() == true
        }.symbol

    private val enumName: IrFunctionSymbol = enumClass.getPropertyGetter("name")!!
}

// Parcel serializer for the java CharSequence interface.
class IrCharSequenceParcelSerializer : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return parcelableCreatorCreateFromParcel(getTextUtilsCharSequenceCreator(), irGet(parcel))
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return textUtilsWriteToParcel(value, irGet(parcel), irGet(flags))
    }
}

// Parcel serializer for Parcelables in the same module, which accesses the writeToParcel/createFromParcel methods without reflection.
class IrEfficientParcelableParcelSerializer(private val irClass: IrClass) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return parcelableCreatorCreateFromParcel(getParcelableCreator(irClass), irGet(parcel))
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return parcelableWriteToParcel(irClass, value, irGet(parcel), irGet(flags))
    }
}

// Parcel serializer for Parcelables using reflection.
// This needs a reference to the parcelize type itself in order to find the correct class loader to use, see KT-20027.
class IrGenericParcelableParcelSerializer(private val parcelizeType: IrType) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return parcelReadParcelable(irGet(parcel), classGetClassLoader(javaClassReference(parcelizeType)))
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return parcelWriteParcelable(irGet(parcel), value, irGet(flags))
    }
}

// Parcel serializer for arbitrary data classes that only contain accessible parcelable members.
class IrDataClassParcelSerializer(
    private val type: IrType,
    private val properties: List<Pair<IrPropertySymbol, IrParcelSerializer>>,
) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression =
        irCall(type.classOrFail.owner.primaryConstructor!!.symbol, type).apply {
            properties.forEachIndexed { index, (_, serializer) ->
                arguments[index] = readParcelWith(serializer, parcel)
            }
        }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression =
        irBlock {
            val temporary = irTemporary(value)
            properties.forEach { (member, serializer) ->
                val receiverValue = irGet(temporary)
                val propertyValue = member.owner.getter?.let { irCall(it).apply { dispatchReceiver = receiverValue } }
                    ?: member.owner.backingField?.let { irGetField(receiverValue, it) }
                    ?: error("$member is a data class property with no backing field?")
                +writeParcelWith(serializer, parcel, flags, propertyValue)
            }
        }
}

// Creates a serializer from a pair of parcel methods of the form reader(ClassLoader)T and writer(T)V.
// This needs a reference to the parcelize type itself in order to find the correct class loader to use, see KT-20027.
class IrParcelSerializerWithClassLoader(
    private val parcelizeType: IrType,
    private val reader: IrSimpleFunctionSymbol,
    private val writer: IrSimpleFunctionSymbol
) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return irCall(reader).apply {
            arguments[0] = irGet(parcel)
            arguments[1] = classGetClassLoader(javaClassReference(parcelizeType))
        }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return irCall(writer).apply {
            arguments[0] = irGet(parcel)
            arguments[1] = value
        }
    }
}

// Parcel serializer using a custom Parceler object.
class IrCustomParcelSerializer(private val parcelerObject: IrClass) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return parcelerCreate(parcelerObject, parcel)
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return parcelerWrite(parcelerObject, parcel, flags, value)
    }
}

// Parcel serializer for array types. This handles both primitive array types (for ShortArray and for primitive arrays using custom element
// parcelers) as well as boxed arrays.
class IrArrayParcelSerializer(
    private val arrayType: IrType,
    private val elementType: IrType,
    private val elementSerializer: IrParcelSerializer
) : IrParcelSerializer {
    private fun AndroidIrBuilder.newArray(size: IrExpression): IrExpression {
        val arrayConstructor: IrFunctionSymbol = if (arrayType.isBoxedArray) {
            context.irBuiltIns.arrayOfNulls
        } else {
            arrayType.classOrNull!!.constructors.single { it.owner.parameters.size == 1 }
        }

        return irCall(arrayConstructor, arrayType).apply {
            if (typeArguments.size >= 1) {
                typeArguments[0] = elementType
            }
            arguments[0] = size
        }
    }

    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return irBlock {
            val arraySize = irTemporary(parcelReadInt(irGet(parcel)))
            val arrayTemporary = irTemporary(newArray(irGet(arraySize)))
            forUntil(irGet(arraySize)) { index ->
                val setter = arrayType.classOrNull!!.getSimpleFunction("set")!!
                +irCall(setter).apply {
                    arguments[0] = irGet(arrayTemporary)
                    arguments[1] = irGet(index)
                    arguments[2] = readParcelWith(elementSerializer, parcel)
                }
            }
            +irGet(arrayTemporary)
        }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return irBlock {
            val arrayTemporary = irTemporary(value)
            val arraySizeSymbol = arrayType.classOrNull!!.getPropertyGetter("size")!!
            val arraySize = irTemporary(irCall(arraySizeSymbol).apply {
                dispatchReceiver = irGet(arrayTemporary)
            })

            +parcelWriteInt(irGet(parcel), irGet(arraySize))

            forUntil(irGet(arraySize)) { index ->
                val getter = context.irBuiltIns.arrayClass.getSimpleFunction("get")!!
                val element = irCall(getter, elementType).apply {
                    arguments[0] = irGet(arrayTemporary)
                    arguments[1] = irGet(index)
                }
                +writeParcelWith(elementSerializer, parcel, flags, element)
            }
        }
    }
}

// Parcel serializer for android SparseArrays. Note that this also needs to handle BooleanSparseArray, in case of a custom element parceler.
class IrSparseArrayParcelSerializer(
    private val sparseArrayClass: IrClass,
    private val elementType: IrType,
    private val elementSerializer: IrParcelSerializer
) : IrParcelSerializer {
    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return irBlock {
            val remainingSizeTemporary = irTemporary(parcelReadInt(irGet(parcel)), isMutable = true)

            val sparseArrayConstructor = sparseArrayClass.constructors.first { irConstructor ->
                irConstructor.parameters.singleOrNull()?.type?.isInt() == true
            }

            val constructorCall = if (sparseArrayClass.typeParameters.isEmpty())
                irCall(sparseArrayConstructor)
            else
                irCallConstructor(sparseArrayConstructor.symbol, listOf(elementType))

            val arrayTemporary = irTemporary(constructorCall.apply {
                arguments[0] = irGet(remainingSizeTemporary)
            })

            +irWhile().apply {
                condition = irNotEquals(irGet(remainingSizeTemporary), irInt(0))
                body = irBlock {
                    val sparseArrayPut = sparseArrayClass.functions.first { function ->
                        function.name.asString() == "put" && function.parameters.size == 3
                    }
                    +irCall(sparseArrayPut).apply {
                        arguments[0] = irGet(arrayTemporary)
                        arguments[1] = parcelReadInt(irGet(parcel))
                        arguments[2] = readParcelWith(elementSerializer, parcel)
                    }

                    val dec = context.irBuiltIns.intClass.getSimpleFunction("dec")!!
                    +irSet(remainingSizeTemporary.symbol, irCall(dec).apply {
                        arguments[0] = irGet(remainingSizeTemporary)
                    })
                }
            }

            +irGet(arrayTemporary)
        }
    }

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        return irBlock {
            val sizeFunction = sparseArrayClass.functions.first { function ->
                function.name.asString() == "size" && function.parameters.size == 1
            }
            val keyAtFunction = sparseArrayClass.functions.first { function ->
                function.name.asString() == "keyAt" && function.parameters.size == 2
            }
            val valueAtFunction = sparseArrayClass.functions.first { function ->
                function.name.asString() == "valueAt" && function.parameters.size == 2
            }

            val arrayTemporary = irTemporary(value)
            val sizeTemporary = irTemporary(irCall(sizeFunction).apply {
                arguments[0] = irGet(arrayTemporary)
            })

            +parcelWriteInt(irGet(parcel), irGet(sizeTemporary))

            forUntil(irGet(sizeTemporary)) { index ->
                +parcelWriteInt(irGet(parcel), irCall(keyAtFunction).apply {
                    arguments[0] = irGet(arrayTemporary)
                    arguments[1] = irGet(index)
                })

                +writeParcelWith(elementSerializer, parcel, flags, irCall(valueAtFunction.symbol, elementType).apply {
                    arguments[0] = irGet(arrayTemporary)
                    arguments[1] = irGet(index)
                })
            }
        }
    }
}

// Parcel serializer for all lists supported by Parcelize. List interfaces use hard-coded default implementations for deserialization.
// List maps to ArrayList, Set maps to LinkedHashSet, NavigableSet and SortedSet map to TreeSet.
class IrListParcelSerializer(
    private val irClass: IrClass,
    private val elementType: IrType,
    private val elementSerializer: IrParcelSerializer
) : IrParcelSerializer {
    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        val sizeFunction = irClass.getPropertyGetter("size")!!
        val iteratorFunction = irClass.getMethodWithoutArguments("iterator")
        val iteratorClass = iteratorFunction.returnType.erasedUpperBound
        val iteratorHasNext = iteratorClass.getMethodWithoutArguments("hasNext")
        val iteratorNext = iteratorClass.getMethodWithoutArguments("next")

        return irBlock {
            val list = irTemporary(value)
            +parcelWriteInt(irGet(parcel), irCall(sizeFunction).apply {
                arguments[0] = irGet(list)
            })
            val iterator = irTemporary(irCall(iteratorFunction).apply {
                arguments[0] = irGet(list)
            })
            +irWhile().apply {
                condition = irCall(iteratorHasNext).apply { dispatchReceiver = irGet(iterator) }
                body = writeParcelWith(elementSerializer, parcel, flags, irCall(iteratorNext.symbol, elementType).apply {
                    arguments[0] = irGet(iterator)
                })
            }
        }
    }

    data class ListSymbols(
        val constructor: IrConstructorSymbol,
        val function: IrSimpleFunctionSymbol,
    )

    private fun listSymbols(symbols: AndroidSymbols): ListSymbols {
        // If the IrClass refers to a concrete type, try to find a constructor with capacity or fall back
        // the default constructor if none exist.
        if (!irClass.isJvmInterface) {
            val constructor = irClass.constructors.find { constructor ->
                constructor.parameters.singleOrNull()?.type?.isInt() == true
            } ?: irClass.constructors.first { constructor -> constructor.parameters.isEmpty() }

            val add = irClass.functions.first { function ->
                function.name.asString() == "add" && function.parameters.size == 2
            }

            return ListSymbols(
                constructor = constructor.symbol,
                function = add.symbol
            )
        }

        return when (irClass.fqNameWhenAvailable?.asString()) {
            "kotlin.collections.MutableList",
            "kotlin.collections.List",
            "java.util.List",
            in BuiltinParcelableTypes.IMMUTABLE_LIST_FQNAMES -> ListSymbols(
                constructor = symbols.arrayListConstructor,
                function = symbols.arrayListAdd
            )
            "kotlin.collections.MutableSet",
            "kotlin.collections.Set",
            "java.util.Set",
            in BuiltinParcelableTypes.IMMUTABLE_SET_FQNAMES -> ListSymbols(
                constructor = symbols.linkedHashSetConstructor,
                function = symbols.linkedHashSetAdd
            )
            "java.util.NavigableSet", "java.util.SortedSet" -> ListSymbols(
                constructor = symbols.treeSetConstructor,
                function = symbols.treeSetAdd
            )
            else -> error("Unknown list interface type: ${irClass.render()}")
        }
    }

    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return irBlock {
            val (constructorSymbol, addSymbol) = listSymbols(androidSymbols)
            val sizeTemporary = irTemporary(parcelReadInt(irGet(parcel)))
            val list = irTemporary(irCall(constructorSymbol).apply {
                if (constructorSymbol.owner.parameters.isNotEmpty())
                    arguments[0] = irGet(sizeTemporary)
            })
            forUntil(irGet(sizeTemporary)) {
                +irCall(addSymbol).apply {
                    arguments[0] = irGet(list)
                    arguments[1] = readParcelWith(elementSerializer, parcel)
                }
            }
            +irGet(list)
        }
    }
}

// Parcel serializer for all maps supported by Parcelize. Map interfaces use hard-coded default implementations for deserialization.
// Map uses LinkedHashMap, while NavigableMap and SortedMap use to TreeMap.
class IrMapParcelSerializer(
    private val irClass: IrClass,
    private val keyType: IrType,
    private val valueType: IrType,
    private val keySerializer: IrParcelSerializer,
    private val valueSerializer: IrParcelSerializer
) : IrParcelSerializer {
    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        val sizeFunction = irClass.getPropertyGetter("size")!!
        val entriesFunction = irClass.getPropertyGetter("entries")!!
        val entrySetClass = entriesFunction.owner.returnType.erasedUpperBound
        val iteratorFunction = entrySetClass.getMethodWithoutArguments("iterator")
        val iteratorClass = iteratorFunction.returnType.erasedUpperBound
        val iteratorHasNext = iteratorClass.getMethodWithoutArguments("hasNext")
        val iteratorNext = iteratorClass.getMethodWithoutArguments("next")
        val elementClass =
            (entriesFunction.owner.returnType as IrSimpleType).arguments.single().upperBound(context.irBuiltIns).erasedUpperBound
        val elementKey = elementClass.getPropertyGetter("key")!!
        val elementValue = elementClass.getPropertyGetter("value")!!

        return irBlock {
            val list = irTemporary(value)
            +parcelWriteInt(irGet(parcel), irCall(sizeFunction).apply {
                dispatchReceiver = irGet(list)
            })
            val iterator = irTemporary(irCall(iteratorFunction).apply {
                dispatchReceiver = irCall(entriesFunction).apply {
                    dispatchReceiver = irGet(list)
                }
            })
            +irWhile().apply {
                condition = irCall(iteratorHasNext).apply { dispatchReceiver = irGet(iterator) }
                body = irBlock {
                    val element = irTemporary(irCall(iteratorNext).apply {
                        dispatchReceiver = irGet(iterator)
                    })
                    +writeParcelWith(keySerializer, parcel, flags, irCall(elementKey, keyType).apply {
                        dispatchReceiver = irGet(element)
                    })
                    +writeParcelWith(valueSerializer, parcel, flags, irCall(elementValue, valueType).apply {
                        dispatchReceiver = irGet(element)
                    })
                }
            }
        }
    }

    data class MapSymbols(
        val constructor: IrConstructorSymbol,
        val function: IrSimpleFunctionSymbol,
    )

    private fun mapSymbols(symbols: AndroidSymbols): MapSymbols {
        // If the IrClass refers to a concrete type, try to find a constructor with capacity or fall back
        // the default constructor if none exist.
        if (!irClass.isJvmInterface) {
            val constructor = irClass.constructors.find { constructor ->
                constructor.parameters.singleOrNull()?.type?.isInt() == true
            } ?: irClass.constructors.find { constructor ->
                constructor.parameters.isEmpty()
            }!!

            val put = irClass.functions.first { function ->
                function.name.asString() == "put" && function.parameters.size == 3
            }

            return MapSymbols(
                constructor = constructor.symbol,
                function = put.symbol,
            )
        }

        return when (irClass.fqNameWhenAvailable?.asString()) {
            "kotlin.collections.MutableMap",
            "kotlin.collections.Map",
            "java.util.Map",
            in BuiltinParcelableTypes.IMMUTABLE_MAP_FQNAMES -> MapSymbols(
                constructor = symbols.linkedHashMapConstructor,
                function = symbols.linkedHashMapPut,
            )
            "java.util.SortedMap",
            "java.util.NavigableMap" -> MapSymbols(
                constructor = symbols.treeMapConstructor,
                function = symbols.treeMapPut
            )
            else -> error("Unknown map interface type: ${irClass.render()}")
        }
    }

    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        return irBlock {
            val (constructorSymbol, putSymbol) = mapSymbols(androidSymbols)
            val sizeTemporary = irTemporary(parcelReadInt(irGet(parcel)))
            val map = irTemporary(irCall(constructorSymbol).apply {
                if (constructorSymbol.owner.parameters.isNotEmpty())
                    arguments[0] = irGet(sizeTemporary)
            })
            forUntil(irGet(sizeTemporary)) {
                +irCall(putSymbol).apply {
                    arguments[0] = irGet(map)
                    arguments[1] = readParcelWith(keySerializer, parcel)
                    arguments[2] = readParcelWith(valueSerializer, parcel)
                }
            }
            +irGet(map)
        }
    }
}

class IrRangeParcelSerializer(
    private val irClass: IrClass,
    private val underlyingTypeSerializer: IrParcelSerializer,
) : IrParcelSerializer {

    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        val firstGetter = irClass.getPropertyGetter("first")!!
        val endGetter = irClass.getPropertyGetter("endInclusive")!!
        return irBlock {
            val range = irTemporary(value)
            +writeParcelWith(underlyingTypeSerializer, parcel, flags, irCall(firstGetter).apply {
                dispatchReceiver = irGet(range)
            })
            +writeParcelWith(underlyingTypeSerializer, parcel, flags, irCall(endGetter).apply {
                dispatchReceiver = irGet(range)
            })
        }
    }

    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        val constructorSymbol = irClass.primaryConstructor!!.symbol
        return irBlock {
            +irCall(constructorSymbol).apply {
                arguments[0] = readParcelWith(underlyingTypeSerializer, parcel)
                arguments[1] = readParcelWith(underlyingTypeSerializer, parcel)
            }
        }
    }
}

class IrUuidParcelSerializer(
    private val irClass: IrClass,
    private val longParcelSerializer: IrParcelSerializer,
): IrParcelSerializer {
    override fun AndroidIrBuilder.writeParcel(parcel: IrValueDeclaration, flags: IrValueDeclaration, value: IrExpression): IrExpression {
        val lambdaFunction = context.irFactory.buildFun {
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            name = SpecialNames.NO_NAME_PROVIDED
            visibility = DescriptorVisibilities.LOCAL
            returnType = context.irBuiltIns.unitType
            modality = Modality.FINAL
        }.apply {
            parent = scope.getLocalDeclarationParent()
            val mostSignBits = addValueParameter("v1", context.irBuiltIns.longType)
            val leastSignBits = addValueParameter("v2", context.irBuiltIns.longType)
            body = irBlockBody {
                +writeParcelWith(longParcelSerializer, parcel, flags, irGet(mostSignBits))
                +writeParcelWith(longParcelSerializer, parcel, flags, irGet(leastSignBits))
            }
        }
        val functionType = context.irBuiltIns.functionN(2).typeWith(
            context.irBuiltIns.longType,
            context.irBuiltIns.longType,
            context.irBuiltIns.unitType
        )
        val lambdaExpr = IrFunctionExpressionImpl(
            startOffset, endOffset, functionType, lambdaFunction, IrStatementOrigin.LAMBDA
        )
        val toLongs = requireNotNull(irClass.getSimpleFunction("toLongs")) { "method kotlin.uuid.Uuid.toLongs not found" }
        return irBlock {
            +irCall(toLongs).apply {
                arguments[0] = value
                arguments[1] = lambdaExpr
            }
        }
    }

    override fun AndroidIrBuilder.readParcel(parcel: IrValueDeclaration): IrExpression {
        val companionReceiver = requireNotNull(irClass.companionObject()?.symbol) {
            "kotlin.uuid.Uuid was expected to have a companion object but none was found"
        }
        val fromLongsFunction = requireNotNull(irClass.companionObject()?.getSimpleFunction("fromLongs")) {
            "function kotlin.uuid.Uuid.fromLongs not found"
        }
        return irBlock {
            +irCall(fromLongsFunction).apply {
                arguments[0] = irGetObject(companionReceiver)
                arguments[1] = readParcelWith(longParcelSerializer, parcel)
                arguments[2] = readParcelWith(longParcelSerializer, parcel)
            }
        }
    }
}
