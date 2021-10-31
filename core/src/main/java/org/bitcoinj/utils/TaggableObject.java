/*
 * Copyright by the original author or authors.
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

package org.bitcoinj.utils;

import com.google.protobuf.ByteString;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * <p>An object that can carry around and possibly serialize a map of strings to immutable byte arrays. Tagged objects
 * can have data stored on them that might be useful for an application developer. For example a wallet can store tags,
 * and thus this wo