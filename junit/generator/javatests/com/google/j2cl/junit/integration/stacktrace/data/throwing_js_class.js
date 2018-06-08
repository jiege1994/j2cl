// Copyright 2018 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

goog.module('nativeStacktraceTest.ThrowingJsClass');

class ThrowingJsClass {
  throwEventually() {
    this.method1();
  }

  method1() {
    this.method2();
  }

  method2() {
    this.method3();
  }

  method3() {
    // We are throwing a Js error here instead of a Java exception, since
    // this exposes a bug in JsCompiler (b/63400239) that does not account
    // for removing the 'new' in the column number.
    // We still use the text here that the Java exception would have produced
    // so that our stack trace asserter does not need to deal with this
    // special case.
    throw new Error('java.lang.RuntimeException: __the_message__!');
  }
}

exports = ThrowingJsClass;
