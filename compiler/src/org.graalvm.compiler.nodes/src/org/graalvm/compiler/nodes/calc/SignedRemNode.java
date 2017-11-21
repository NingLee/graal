/*
 * Copyright (c) 2011, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.nodes.calc;

import org.graalvm.compiler.core.common.type.IntegerStamp;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.graph.spi.CanonicalizerTool;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.spi.LIRLowerable;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;

import jdk.vm.ci.code.CodeUtil;

@NodeInfo(shortName = "%")
public class SignedRemNode extends IntegerDivRemNode implements LIRLowerable {

    public static final NodeClass<SignedRemNode> TYPE = NodeClass.create(SignedRemNode.class);

    public SignedRemNode(ValueNode x, ValueNode y) {
        this(TYPE, x, y);
    }

    protected SignedRemNode(NodeClass<? extends SignedRemNode> c, ValueNode x, ValueNode y) {
        super(c, IntegerStamp.OPS.getRem().foldStamp(x.stamp(NodeView.DEFAULT), y.stamp(NodeView.DEFAULT)), Op.REM, Type.SIGNED, x, y);
    }

    @Override
    public boolean inferStamp() {
        return updateStamp(IntegerStamp.OPS.getRem().foldStamp(getX().stamp(NodeView.DEFAULT), getY().stamp(NodeView.DEFAULT)));
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forX, ValueNode forY) {
        if (forX.isConstant() && forY.isConstant()) {
            @SuppressWarnings("hiding")
            long y = forY.asJavaConstant().asLong();
            if (y == 0) {
                return this; // this will trap, can not canonicalize
            }
            return ConstantNode.forIntegerStamp(stamp(NodeView.DEFAULT), forX.asJavaConstant().asLong() % y);
        } else if (forY.isConstant() && forX.stamp(NodeView.DEFAULT) instanceof IntegerStamp && forY.stamp(NodeView.DEFAULT) instanceof IntegerStamp) {
            long constY = forY.asJavaConstant().asLong();
            IntegerStamp xStamp = (IntegerStamp) forX.stamp(NodeView.DEFAULT);
            IntegerStamp yStamp = (IntegerStamp) forY.stamp(NodeView.DEFAULT);
            if (constY < 0 && constY != CodeUtil.minValue(yStamp.getBits())) {
                return new SignedRemNode(forX, ConstantNode.forIntegerStamp(yStamp, -constY)).canonical(tool);
            }

            if (constY == 1) {
                return ConstantNode.forIntegerStamp(stamp(NodeView.DEFAULT), 0);
            } else if (CodeUtil.isPowerOf2(constY)) {
                if (xStamp.isPositive()) {
                    // x & (y - 1)
                    return new AndNode(forX, ConstantNode.forIntegerStamp(stamp(NodeView.DEFAULT), constY - 1));
                } else if (xStamp.isNegative()) {
                    // -((-x) & (y - 1))
                    return new NegateNode(new AndNode(new NegateNode(forX), ConstantNode.forIntegerStamp(stamp(NodeView.DEFAULT), constY - 1)));
                } else {
                    // x - ((x / y) << log2(y))
                    NodeView view = NodeView.from(tool);
                    return SubNode.create(forX, LeftShiftNode.create(SignedDivNode.canonical(forX, constY, view), ConstantNode.forInt(CodeUtil.log2(constY)), view), view);
                }
            }
        }
        return this;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        gen.setResult(this, gen.getLIRGeneratorTool().getArithmetic().emitRem(gen.operand(getX()), gen.operand(getY()), gen.state(this)));
    }
}
