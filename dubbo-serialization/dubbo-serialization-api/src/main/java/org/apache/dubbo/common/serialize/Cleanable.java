/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.serialize;

/**
 * Interface defines that the object is cleanable.
 */
//该接口是清理接口，定义了一个清理方法。目前只有kryo实现的时候，
// 完成序列化或反序列化，需要做清理。通过实现该接口，执行清理的逻辑。
public interface Cleanable {

    /**
     * 清理
     * Implementations must implement this cleanup method
     */
    void cleanup();
}
