/*
 * Copyright 2013 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.script;

import org.bitcoinj.core.Utils;
import com.google.common.base.Objects;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkState;
import static org.bitcoinj.script.ScriptOpCodes.*;

/**
 * A script element that is either a data push (signature, pubkey, etc) or a non-push (logic, numeric, etc) operation.
 */
public class ScriptChunk {
    /** Operation to be executed. Opcodes are defined in {@link ScriptOpCodes}. */
    public final int opcode;
    /**
     * For push operations, this is the vector to be pushed on the stack. For {@link ScriptOpCodes#OP_0}, the vector is
     * empty. Null for non-push operations.
     */
    @Nullable
    public final byte[] data;
    private int startLocationInProgram;

    public