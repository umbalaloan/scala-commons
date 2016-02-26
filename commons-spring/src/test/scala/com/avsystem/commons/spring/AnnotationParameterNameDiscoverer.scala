package com.avsystem.commons
package spring

import java.lang.reflect.{Constructor, Method}

import org.springframework.core.ParameterNameDiscoverer

/**
  * Author: ghik
  * Created: 11/09/15.
  */
class AnnotationParameterNameDiscoverer extends ParameterNameDiscoverer {
  def getParameterNames(ctor: Constructor[_]): Array[String] =
    Option(ctor.getAnnotation(classOf[ParamNames])).map(_.value).orNull

  def getParameterNames(method: Method): Array[String] =
    Option(method.getAnnotation(classOf[ParamNames])).map(_.value).orNull
}
