/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.ast;

import com.google.j2cl.ast.processors.Visitable;

import java.util.ArrayList;
import java.util.List;

/**
 * Class for new array expression.
 */
@Visitable
public class NewArray extends Expression {
  @Visitable List<Expression> dimensionExpressions = new ArrayList<>();
  @Visitable TypeReference leafTypeRef;

  public NewArray(List<Expression> dimensionExpressions, TypeReference leafTypeRef) {
    this.dimensionExpressions.addAll(dimensionExpressions);
    this.leafTypeRef = leafTypeRef;
  }

  public List<Expression> getDimensionExpressions() {
    return dimensionExpressions;
  }

  public TypeReference getLeafTypeRef() {
    return leafTypeRef;
  }

  @Override
  public NewArray accept(Processor processor) {
    return Visitor_NewArray.visit(processor, this);
  }
}
