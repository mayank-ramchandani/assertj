/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Copyright 2012-2023 the original author or authors.
 */
package org.assertj.core.api.recursive.comparison;

import static java.lang.String.format;
import static java.util.Objects.deepEquals;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;
import static org.assertj.core.api.recursive.comparison.ComparisonDifference.rootComparisonDifference;
import static org.assertj.core.api.recursive.comparison.DualValue.DEFAULT_ORDERED_COLLECTION_TYPES;
import static org.assertj.core.api.recursive.comparison.FieldLocation.rootFieldLocation;
import static org.assertj.core.util.IterableUtil.sizeOf;
import static org.assertj.core.util.Lists.list;
import static org.assertj.core.util.Sets.newHashSet;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Stream;

import org.assertj.core.internal.DeepDifference;

/**
 * Based on {@link DeepDifference} but takes a {@link RecursiveComparisonConfiguration}, {@link DeepDifference}
 * being itself based on the deep equals implementation of <a href="https://github.com/jdereg/java-util">https://github.com/jdereg/java-util</a>
 *
 * @author John DeRegnaucourt (john@cedarsoftware.com)
 * @author Pascal Schumacher
 */
public class RecursiveComparisonDifferenceCalculator {

  private static final String DIFFERENT_ACTUAL_AND_EXPECTED_FIELD_TYPES = "expected field is %s but actual field is not (%s)";
  private static final String ACTUAL_IS_AN_ENUM_WHILE_EXPECTED_IS_NOT = "expected field is a %s but actual field is an enum";
  private static final String ACTUAL_NOT_ORDERED_COLLECTION = "expected field is an ordered collection but actual field is not (%s), ordered collections are: "
                                                              + describeOrderedCollectionTypes();

  private static final String VALUE_FIELD_NAME = "value";
  private static final String ARRAY_FIELD_NAME = "array";
  private static final String STRICT_TYPE_ERROR = "the fields are considered different since the comparison enforces strict type check and %s is not a subtype of %s";
  private static final String DIFFERENT_SIZE_ERROR = "actual and expected values are %s of different size, actual size=%s when expected size=%s";
  private static final String MISSING_FIELDS = "%s can't be compared to %s as %s does not declare all %s fields, it lacks these: %s";
  private static final Map<Class<?>, Boolean> customEquals = new ConcurrentHashMap<>();

  private static class ComparisonState {
    // Not using a Set as we want to precisely track visited values, a set would remove duplicates
    VisitedDualValues visitedDualValues;
    List<ComparisonDifference> differences = new ArrayList<>();
    DualValueDeque dualValuesToCompare;
    RecursiveComparisonConfiguration recursiveComparisonConfiguration;

    public ComparisonState(VisitedDualValues visitedDualValues,
                           RecursiveComparisonConfiguration recursiveComparisonConfiguration) {
      this.visitedDualValues = visitedDualValues;
      this.dualValuesToCompare = new DualValueDeque(recursiveComparisonConfiguration);
      this.recursiveComparisonConfiguration = recursiveComparisonConfiguration;
    }

    void addDifference(DualValue dualValue) {
      addDifference(dualValue, null);
    }

    void addDifference(DualValue dualValue, String description) {
      String customErrorMessage = getCustomErrorMessage(dualValue);
      ComparisonDifference comparisonDifference = new ComparisonDifference(dualValue, description, customErrorMessage);
      differences.add(comparisonDifference);
      // track the difference for the given dual values, in case we visit the same dual values again
      visitedDualValues.registerComparisonDifference(dualValue, comparisonDifference);
    }

    void addKeyDifference(DualValue parentDualValue, Object actualKey, Object expectedKey) {
      differences.add(new ComparisonKeyDifference(parentDualValue, actualKey, expectedKey));
    }

    public List<ComparisonDifference> getDifferences() {
      Collections.sort(differences);
      return differences;
    }

    public boolean hasDualValuesToCompare() {
      return !dualValuesToCompare.isEmpty();
    }

    public DualValue pickDualValueToCompare() {
      return dualValuesToCompare.removeFirst();
    }

    private void registerForComparison(DualValue dualValue) {
      dualValuesToCompare.addFirst(dualValue);
    }

    private void initDualValuesToCompare(Object actual, Object expected, FieldLocation nodeLocation) {
      DualValue dualValue = new DualValue(nodeLocation, actual, expected);
      boolean mustCompareNodesRecursively = mustCompareNodesRecursively(dualValue);
      if (dualValue.hasNoNullValues() && mustCompareNodesRecursively) {
        // disregard the equals method and start comparing fields
        // TODO should fail if actual and expected don't have the same fields (taking into account ignored/compared fields)
        Set<String> actualChildrenNodeNamesToCompare = recursiveComparisonConfiguration.getActualChildrenNodeNamesToCompare(dualValue);
        if (!actualChildrenNodeNamesToCompare.isEmpty()) {
          // fields to ignore are evaluated when adding their corresponding dualValues to dualValuesToCompare which filters
          // ignored fields according to recursiveComparisonConfiguration
          Set<String> expectedChildrenNodesNames = recursiveComparisonConfiguration.getChildrenNodeNamesOf(expected);
          if (expectedChildrenNodesNames.containsAll(actualChildrenNodeNamesToCompare)) {
            // we compare actual fields vs expected, ignoring expected additional fields
            for (String actualChildNodeName : actualChildrenNodeNamesToCompare) {
              Object actualChildNodeValue = recursiveComparisonConfiguration.getValue(actualChildNodeName, actual);
              Object expectedChildNodeValue = recursiveComparisonConfiguration.getValue(actualChildNodeName, expected);
              DualValue childNodeDualValue = new DualValue(nodeLocation.field(actualChildNodeName), actualChildNodeValue,
                                                           expectedChildNodeValue);
              registerForComparison(childNodeDualValue);
            }
          } else {
            registerForComparison(dualValue);
          }
        } else {
          registerForComparison(dualValue);
        }
      } else {
        registerForComparison(dualValue);
      }
    }

    private boolean mustCompareNodesRecursively(DualValue dualValue) {
      return !recursiveComparisonConfiguration.hasCustomComparator(dualValue)
             && !shouldHonorEquals(dualValue, recursiveComparisonConfiguration)
             && dualValue.hasNoContainerValues();
    }

    private String getCustomErrorMessage(DualValue dualValue) {
      String fieldName = dualValue.getConcatenatedPath();
      // field custom messages take precedence over type messages
      if (recursiveComparisonConfiguration.hasCustomMessageForField(fieldName)) {
        return recursiveComparisonConfiguration.getMessageForField(fieldName);
      }
      Class<?> fieldType = dualValue.actual != null ? dualValue.actual.getClass() : dualValue.expected.getClass();
      if (recursiveComparisonConfiguration.hasCustomMessageForType(fieldType)) {
        return recursiveComparisonConfiguration.getMessageForType(fieldType);
      }
      return null;
    }
  }

  /**
   * Compare two objects for differences by doing a 'deep' comparison. This will traverse the
   * Object graph and perform either a field-by-field comparison on each
   * object (if not .equals() method has been overridden from Object), or it
   * will call the customized .equals() method if it exists.
   * <p>
   *
   * This method handles cycles correctly, for example A-&gt;B-&gt;C-&gt;A.
   * Suppose a and a' are two separate instances of the A with the same values
   * for all fields on A, B, and C. Then a.deepEquals(a') will return an empty list. It
   * uses cycle detection storing visited objects in a Set to prevent endless
   * loops.
   *
   * @param actual Object one to compare
   * @param expected Object two to compare
   * @param recursiveComparisonConfiguration the recursive comparison configuration
   * @return the list of differences found or an empty list if objects are equivalent.
   *         Equivalent means that all field values of both subgraphs are the same,
   *         either at the field level or via the respectively encountered overridden
   *         .equals() methods during traversal.
   */
  public List<ComparisonDifference> determineDifferences(Object actual, Object expected,
                                                         RecursiveComparisonConfiguration recursiveComparisonConfiguration) {
    if (recursiveComparisonConfiguration.isInStrictTypeCheckingMode() && expectedTypeIsNotSubtypeOfActualType(actual, expected)) {
      return list(expectedAndActualTypeDifference(actual, expected));
    }
    return determineDifferences(actual, expected, rootFieldLocation(), new VisitedDualValues(), recursiveComparisonConfiguration);
  }

  // TODO keep track of ignored fields in an RecursiveComparisonExecution class ?

  private static List<ComparisonDifference> determineDifferences(Object actual, Object expected,
                                                                 FieldLocation fieldLocation,
                                                                 VisitedDualValues visitedDualValues,
                                                                 RecursiveComparisonConfiguration recursiveComparisonConfiguration) {
    ComparisonState comparisonState = new ComparisonState(visitedDualValues, recursiveComparisonConfiguration);
    comparisonState.initDualValuesToCompare(actual, expected, fieldLocation);

    while (comparisonState.hasDualValuesToCompare()) {
      DualValue dualValue = comparisonState.pickDualValueToCompare();
      Optional<List<ComparisonDifference>> comparisonDifferences = getRegisteredComparisonDifferences(dualValue, comparisonState);

      if (comparisonDifferences.isPresent()) {
        if (!comparisonDifferences.get().isEmpty()) {
          comparisonState.addDifference(dualValue, "already visited node but now location is: " + dualValue.fieldLocation);
        }
        continue;
      }

      processDualValue(dualValue, comparisonState, recursiveComparisonConfiguration);
    }

    return comparisonState.getDifferences();
  }

  private static void processDualValue(DualValue dualValue, ComparisonState comparisonState,
                                       RecursiveComparisonConfiguration recursiveComparisonConfiguration) {
    if (dualValue.hasPotentialCyclingValues()) {
      registerVisitedDualValue(dualValue, comparisonState);
    }

    final Object actualFieldValue = dualValue.actual;
    final Object expectedFieldValue = dualValue.expected;

    if (recursiveComparisonConfiguration.hasCustomComparator(dualValue)) {
      compareWithCustomComparator(dualValue, comparisonState, recursiveComparisonConfiguration);
    } else if (actualFieldValue == expectedFieldValue) {
      // Do nothing, values are the same
    } else if (actualFieldValue == null || expectedFieldValue == null) {
      comparisonState.addDifference(dualValue);
    } else if (dualValue.isExpectedAnEnum() || dualValue.isActualAnEnum()) {
      compareAsEnums(dualValue, comparisonState, recursiveComparisonConfiguration);
    } else if (dualValue.isExpectedFieldAnArray()) {
      compareArrays(dualValue, comparisonState);
    } else if (dualValue.isExpectedFieldAnOrderedCollection() && !recursiveComparisonConfiguration.shouldIgnoreCollectionOrder(dualValue.fieldLocation)) {
      compareOrderedCollections(dualValue, comparisonState);
    } else if (dualValue.isExpectedFieldAnIterable()) {
      compareUnorderedIterables(dualValue, comparisonState);
    } else if (dualValue.isExpectedFieldAnOptional()) {
      compareOptional(dualValue, comparisonState);
    } else if (dualValue.isExpectedFieldASortedMap()) {
      compareSortedMap(dualValue, comparisonState);
    } else if (dualValue.isExpectedFieldAMap()) {
      compareUnorderedMap(dualValue, comparisonState);
    } else if (isExpectedFieldAnAtomicType(dualValue)) {
      compareAtomicType(dualValue, comparisonState);
    } else if (shouldHonorEquals(dualValue, recursiveComparisonConfiguration)) {
      compareWithEqualsMethod(dualValue, comparisonState);
    } else if (expectedTypeIsNotSubtypeOfActualType(dualValue)) {
      comparisonState.addDifference(dualValue,
        format(STRICT_TYPE_ERROR, dualValue.expected.getClass().getName(), dualValue.actual.getClass().getName()));
    } else {
      compareChildrenNodes(dualValue, comparisonState, recursiveComparisonConfiguration);
    }
  }

  private static Optional<List<ComparisonDifference>> getRegisteredComparisonDifferences(DualValue dualValue, ComparisonState comparisonState) {
    return comparisonState.visitedDualValues.registeredComparisonDifferencesOf(dualValue);
  }

  private static void compareWithCustomComparator(DualValue dualValue, ComparisonState comparisonState, RecursiveComparisonConfiguration recursiveComparisonConfiguration) {
    if (!areDualValueEqual(dualValue, recursiveComparisonConfiguration)) {
      comparisonState.addDifference(dualValue);
    }
  }

  private static void compareWithEqualsMethod(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.actual.equals(dualValue.expected)) {
      comparisonState.addDifference(dualValue);
    }
  }

  private static void compareChildrenNodes(DualValue dualValue, ComparisonState comparisonState, RecursiveComparisonConfiguration recursiveComparisonConfiguration) {
    Set<String> actualChildrenNodeNamesToCompare = recursiveComparisonConfiguration.getActualChildrenNodeNamesToCompare(dualValue);
    Set<String> expectedChildrenNodesNames = recursiveComparisonConfiguration.getChildrenNodeNamesOf(dualValue.expected);

    if (!expectedChildrenNodesNames.containsAll(actualChildrenNodeNamesToCompare)) {
      Set<String> actualNodesNamesNotInExpected = newHashSet(actualChildrenNodeNamesToCompare);
      actualNodesNamesNotInExpected.removeAll(expectedChildrenNodesNames);
      String missingNodes = actualNodesNamesNotInExpected.toString();
      String expectedClassName = dualValue.expected.getClass().getName();
      String actualClassName = dualValue.actual.getClass().getName();
      String missingNodesDescription = format(MISSING_FIELDS, actualClassName, expectedClassName,
        dualValue.expected.getClass().getSimpleName(), dualValue.actual.getClass().getSimpleName(),
        missingNodes);
      comparisonState.addDifference(dualValue, missingNodesDescription);
    } else {
      for (String actualChildNodeName : actualChildrenNodeNamesToCompare) {
        if (expectedChildrenNodesNames.contains(actualChildNodeName)) {
          Object actualChildNodeValue = recursiveComparisonConfiguration.getValue(actualChildNodeName, dualValue.actual);
          Object expectedChildNodeValue = recursiveComparisonConfiguration.getValue(actualChildNodeName, dualValue.expected);
          DualValue newDualValue = new DualValue(dualValue.fieldLocation.field(actualChildNodeName),
            actualChildNodeValue, expectedChildNodeValue);
          comparisonState.registerForComparison(newDualValue);
        }
      }
    }
  }

  private static void compareAtomicType(DualValue dualValue, ComparisonState comparisonState) {
    if (!actualFieldValueEqualsExpectedFieldValue(dualValue)) {
      comparisonState.addDifference(dualValue);
    }
  }

  private static void registerVisitedDualValue(DualValue dualValue, ComparisonState comparisonState) {
    comparisonState.visitedDualValues.registerVisitedDualValue(dualValue);
  }

  private static boolean isExpectedFieldAnAtomicType(DualValue dualValue) {
    return dualValue.expected instanceof AtomicBoolean
      || dualValue.expected instanceof AtomicInteger
      || dualValue.expected instanceof AtomicIntegerArray
      || dualValue.expected instanceof AtomicLong
      || dualValue.expected instanceof AtomicLongArray
      || dualValue.expected instanceof AtomicReference
      || dualValue.expected instanceof AtomicReferenceArray;
  }

  private static boolean actualFieldValueEqualsExpectedFieldValue(DualValue dualValue) {
    if (dualValue.actual == null || dualValue.expected == null) {
      return dualValue.actual == dualValue.expected;
    }
    return dualValue.actual.equals(dualValue.expected);
  }

  // avoid comparing enum recursively since they contain static fields which are ignored in recursive comparison
  // this would make different field enum value to be considered the same!
  private static void compareAsEnums(final DualValue dualValue, ComparisonState comparisonState,
                                     RecursiveComparisonConfiguration recursiveComparisonConfiguration) {
    if (recursiveComparisonConfiguration.isInStrictTypeCheckingMode()) {
      // use == to check that both actual and expected values and types are the same
      if (dualValue.actual != dualValue.expected) comparisonState.addDifference(dualValue);
      return;
    }
    if (dualValue.isActualAnEnum() && dualValue.isExpectedAnEnum()) {
      Enum<?> expectedEnum = (Enum<?>) dualValue.expected;
      Enum<?> actualEnum = (Enum<?>) dualValue.actual;
      // we must only compare actual and expected enum by value but not by type
      if (!actualEnum.name().equals(expectedEnum.name())) comparisonState.addDifference(dualValue);
      return;
    }
    if (!recursiveComparisonConfiguration.isComparingEnumAgainstStringAllowed()) {
      // either actual or expected is not an enum, not ok as we haven't allowed comparing enums to strings fields
      enumComparedToDifferentTypeError(dualValue, comparisonState);
      return;
    }
    if (dualValue.isExpectedAnEnum() && dualValue.actual instanceof String) {
      Enum<?> expectedEnum = (Enum<?>) dualValue.expected;
      if (!expectedEnum.name().equals(dualValue.actual.toString())) comparisonState.addDifference(dualValue);
      return;
    }
    if (dualValue.isActualAnEnum() && dualValue.expected instanceof String) {
      Enum<?> actualEnum = (Enum<?>) dualValue.actual;
      if (!actualEnum.name().equals(dualValue.expected.toString())) comparisonState.addDifference(dualValue);
      return;
    }
    // either actual or expected is not an enum and the other type is not a string so invalid type
    enumComparedToDifferentTypeError(dualValue, comparisonState);
  }

  private static void enumComparedToDifferentTypeError(DualValue dualValue, ComparisonState comparisonState) {
    String typeErrorMessage = dualValue.isExpectedAnEnum()
        ? differentTypeErrorMessage(dualValue, "an enum")
        : format(ACTUAL_IS_AN_ENUM_WHILE_EXPECTED_IS_NOT, dualValue.expected.getClass().getCanonicalName());
    comparisonState.addDifference(dualValue, typeErrorMessage);
  }

  private static boolean shouldHonorEquals(DualValue dualValue,
                                           RecursiveComparisonConfiguration recursiveComparisonConfiguration) {
    // since java 17 we can't introspect java types and get their fields so by default we compare them with equals
    // unless for some container like java types: iterables, array, optional, atomic values where we take the contained values
    // through accessors and register them in the recursive comparison.
    boolean shouldHonorJavaTypeEquals = dualValue.hasSomeJavaTypeValue() && !dualValue.isExpectedAContainer();
    return shouldHonorJavaTypeEquals || shouldHonorOverriddenEquals(dualValue, recursiveComparisonConfiguration);
  }

  private static boolean shouldHonorOverriddenEquals(DualValue dualValue,
                                                     RecursiveComparisonConfiguration recursiveComparisonConfiguration) {
    boolean shouldNotIgnoreOverriddenEqualsIfAny = !recursiveComparisonConfiguration.shouldIgnoreOverriddenEqualsOf(dualValue);
    return shouldNotIgnoreOverriddenEqualsIfAny && dualValue.actual != null && hasOverriddenEquals(dualValue.actual.getClass());
  }

  private static void compareArrays(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldAnArray()) {
      // at the moment we only allow comparing arrays with arrays but we might allow comparing to collections later on
      // but only if we are not in strict type mode.
      comparisonState.addDifference(dualValue, differentTypeErrorMessage(dualValue, "an array"));
      return;
    }
    // both values in dualValue are arrays
    int actualArrayLength = Array.getLength(dualValue.actual);
    int expectedArrayLength = Array.getLength(dualValue.expected);
    if (actualArrayLength != expectedArrayLength) {
      comparisonState.addDifference(dualValue, format(DIFFERENT_SIZE_ERROR, "arrays", actualArrayLength, expectedArrayLength));
      // no need to inspect elements, arrays are not equal as they don't have the same size
      return;
    }
    // register each pair of actual/expected elements for recursive comparison
    FieldLocation arrayFieldLocation = dualValue.fieldLocation;
    for (int i = 0; i < actualArrayLength; i++) {
      Object actualElement = Array.get(dualValue.actual, i);
      Object expectedElement = Array.get(dualValue.expected, i);
      FieldLocation elementFieldLocation = arrayFieldLocation.field(format("[%d]", i));
      comparisonState.registerForComparison(new DualValue(elementFieldLocation, actualElement, expectedElement));
    }
  }

  /*
   * Deeply compare two Collections that must be same length and in same order.
   */
  private static void compareOrderedCollections(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldAnOrderedCollection()) {
      // at the moment if expected is an ordered collection then actual should also be one
      comparisonState.addDifference(dualValue,
                                    format(ACTUAL_NOT_ORDERED_COLLECTION, dualValue.actual.getClass().getCanonicalName()));
      return;
    }

    Collection<?> actualCollection = (Collection<?>) dualValue.actual;
    Collection<?> expectedCollection = (Collection<?>) dualValue.expected;
    if (actualCollection.size() != expectedCollection.size()) {
      comparisonState.addDifference(dualValue, format(DIFFERENT_SIZE_ERROR, "collections", actualCollection.size(),
                                                      expectedCollection.size()));
      // no need to inspect elements, arrays are not equal as they don't have the same size
      return;
    }
    // register a pair of elements with same index for later comparison as we compare elements in order
    Iterator<?> expectedIterator = expectedCollection.iterator();
    int i = 0;
    for (Object element : actualCollection) {
      FieldLocation elementFieldLocation = dualValue.fieldLocation.field(format("[%d]", i));
      DualValue elementDualValue = new DualValue(elementFieldLocation, element, expectedIterator.next());
      comparisonState.registerForComparison(elementDualValue);
      i++;
    }
  }

  private static String differentTypeErrorMessage(DualValue dualValue, String actualTypeDescription) {
    return format(DIFFERENT_ACTUAL_AND_EXPECTED_FIELD_TYPES,
                  actualTypeDescription, dualValue.actual.getClass().getCanonicalName());
  }

  private static void compareUnorderedIterables(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldAnIterable()) {
      // at the moment we only compare iterable with iterables (but we might allow arrays too)
      comparisonState.addDifference(dualValue, differentTypeErrorMessage(dualValue, "an iterable"));
      return;
    }
    Iterable<?> actual = (Iterable<?>) dualValue.actual;
    Iterable<?> expected = (Iterable<?>) dualValue.expected;
    int actualSize = sizeOf(actual);
    int expectedSize = sizeOf(expected);
    if (actualSize != expectedSize) {
      comparisonState.addDifference(dualValue, format(DIFFERENT_SIZE_ERROR, "collections", actualSize, expectedSize));
      // no need to inspect elements, iterables are not equal as they don't have the same size
      return;
    }
    Map<Integer, ? extends List<?>> actualByHashCode = stream(actual.spliterator(), false).collect(groupingBy(Objects::hashCode,
                                                                                                              toList()));
    List<Object> expectedElementsNotFound = list();
    for (Object expectedElement : expected) {
      boolean expectedElementMatched = false;
      // speed up comparison by selecting actual elements matching expected hash code, note that the hash code might not be
      // relevant if fields used to compute it are ignored in the recursive comparison, it's a good heuristic though to check
      // the first actual elements that could match the expected one, worst case we compare all actual elements.
      Integer expectedHash = Objects.hashCode(expectedElement);
      List<?> actualHashBucket = actualByHashCode.get(expectedHash);
      if (actualHashBucket != null) {
        Iterator<?> actualIterator = actualHashBucket.iterator();
        expectedElementMatched = searchIterableForElement(actualIterator, expectedElement, dualValue, comparisonState);
      }
      // It may be that expectedElement matches an actual element in a different hash bucket, to account for this, we check the
      // other actual elements for matches. This may result in O(n^2) complexity in the worst case.
      if (!expectedElementMatched) {
        for (Map.Entry<Integer, ? extends List<?>> entry : actualByHashCode.entrySet()) {
          // avoid checking the same bucket twice
          if (entry.getKey().equals(expectedHash)) continue;
          Iterator<?> actualIterator = entry.getValue().iterator();
          expectedElementMatched = searchIterableForElement(actualIterator, expectedElement, dualValue, comparisonState);
          if (expectedElementMatched) break;
        }
        if (!expectedElementMatched) expectedElementsNotFound.add(expectedElement);
      }
    }

    if (!expectedElementsNotFound.isEmpty()) {
      String unmatched = format("The following expected elements were not matched in the actual %s:%n  %s",
        actual.getClass().getSimpleName(), expectedElementsNotFound);
      comparisonState.addDifference(dualValue, unmatched);
      // TODO could improve the error by listing the actual elements not in expected but that would need
      // another double loop inverting actual and expected to find the actual elements not matched in expected
    }
  }

  private static boolean searchIterableForElement(Iterator<?> actualIterator, Object expectedElement,
                                                  DualValue dualValue, ComparisonState comparisonState) {
    while (actualIterator.hasNext()) {
      Object actualElement = actualIterator.next();
      // we need to get the currently visited dual values otherwise a cycle would cause an infinite recursion.
      List<ComparisonDifference> differences = determineDifferences(actualElement, expectedElement,
                                                                    dualValue.fieldLocation,
                                                                    comparisonState.visitedDualValues,
                                                                    comparisonState.recursiveComparisonConfiguration);
      if (differences.isEmpty()) {
        // found an element in actual matching expectedElement, remove it as it can't be used to match other expected elements
        actualIterator.remove();
        return true;
      }
    }
    return false;
  }

  // TODO replace by ordered map
  private static <K, V> void compareSortedMap(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldASortedMap()) {
      // at the moment we only compare iterable with iterables (but we might allow arrays too)
      comparisonState.addDifference(dualValue, differentTypeErrorMessage(dualValue, "a sorted map"));
      return;
    }

    Map<?, ?> actualMap = (Map<?, ?>) dualValue.actual;
    @SuppressWarnings("unchecked")
    Map<K, V> expectedMap = (Map<K, V>) dualValue.expected;
    if (actualMap.size() != expectedMap.size()) {
      comparisonState.addDifference(dualValue, format(DIFFERENT_SIZE_ERROR, "sorted maps", actualMap.size(), expectedMap.size()));
      // no need to inspect entries, maps are not equal as they don't have the same size
      return;
    }
    Iterator<Map.Entry<K, V>> expectedMapEntries = expectedMap.entrySet().iterator();
    for (Map.Entry<?, ?> actualEntry : actualMap.entrySet()) {
      Map.Entry<?, ?> expectedEntry = expectedMapEntries.next();
      // check keys are matched before comparing values as keys represents a field
      if (!java.util.Objects.equals(actualEntry.getKey(), expectedEntry.getKey())) {
        // report a missing key/field.
        comparisonState.addKeyDifference(dualValue, actualEntry.getKey(), expectedEntry.getKey());
      } else {
        // as the key/field match we can simply compare field/key values
        FieldLocation keyFieldLocation = keyFieldLocation(dualValue.fieldLocation, actualEntry.getKey());
        comparisonState.registerForComparison(new DualValue(keyFieldLocation, actualEntry.getValue(), expectedEntry.getValue()));
      }
    }
  }

  private static void compareUnorderedMap(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldAMap()) {
      comparisonState.addDifference(dualValue, differentTypeErrorMessage(dualValue, "a map"));
      return;
    }

    Map<?, ?> actualMap = (Map<?, ?>) dualValue.actual;
    Map<?, ?> expectedMap = (Map<?, ?>) dualValue.expected;
    if (actualMap.size() != expectedMap.size()) {
      comparisonState.addDifference(dualValue, format(DIFFERENT_SIZE_ERROR, "maps", actualMap.size(), expectedMap.size()));
      // no need to inspect entries, maps are not equal as they don't have the same size
      return;
    }
    // actual and expected maps same size but do they have the same keys?
    Set<?> expectedKeysNotFound = new LinkedHashSet<>(expectedMap.keySet());
    expectedKeysNotFound.removeAll(actualMap.keySet());
    if (!expectedKeysNotFound.isEmpty()) {
      comparisonState.addDifference(dualValue, format("The following keys were not found in the actual map value:%n  %s",
                                                      expectedKeysNotFound));
      return;
    }
    // actual and expected maps have the same keys, we need now to compare their values
    for (Object key : expectedMap.keySet()) {
      FieldLocation keyFieldLocation = keyFieldLocation(dualValue.fieldLocation, key);
      comparisonState.registerForComparison(new DualValue(keyFieldLocation, actualMap.get(key), expectedMap.get(key)));
    }
  }

  private static FieldLocation keyFieldLocation(FieldLocation parentFieldLocation, Object key) {
    return key == null ? parentFieldLocation : parentFieldLocation.field(key.toString());
  }

  private static void compareOptional(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldAnOptional()) {
      comparisonState.addDifference(dualValue, differentTypeErrorMessage(dualValue, "an Optional"));
      return;
    }
    Optional<?> actual = (Optional<?>) dualValue.actual;
    Optional<?> expected = (Optional<?>) dualValue.expected;
    if (actual.isPresent() != expected.isPresent()) {
      comparisonState.addDifference(dualValue);
      return;
    }
    // either both are empty or present
    if (!actual.isPresent()) return; // both optional are empty => end of the comparison
    // both are present, we have to compare their values recursively
    Object value1 = actual.get();
    Object value2 = expected.get();
    // we add VALUE_FIELD_NAME to the path since we register Optional.value fields.
    comparisonState.registerForComparison(new DualValue(dualValue.fieldLocation.field(VALUE_FIELD_NAME), value1, value2));
  }

  private static void compareAtomicBoolean(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldAnAtomicBoolean()) {
      comparisonState.addDifference(dualValue, differentTypeErrorMessage(dualValue, "an AtomicBoolean"));
      return;
    }
    AtomicBoolean actual = (AtomicBoolean) dualValue.actual;
    AtomicBoolean expected = (AtomicBoolean) dualValue.expected;
    Object value1 = actual.get();
    Object value2 = expected.get();
    // we add VALUE_FIELD_NAME to the path since we register AtomicBoolean.value fields.
    comparisonState.registerForComparison(new DualValue(dualValue.fieldLocation.field(VALUE_FIELD_NAME), value1, value2));
  }

  private static void compareAtomicInteger(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldAnAtomicInteger()) {
      comparisonState.addDifference(dualValue, differentTypeErrorMessage(dualValue, "an AtomicInteger"));
      return;
    }
    AtomicInteger actual = (AtomicInteger) dualValue.actual;
    AtomicInteger expected = (AtomicInteger) dualValue.expected;
    Object value1 = actual.get();
    Object value2 = expected.get();
    // we add VALUE_FIELD_NAME to the path since we register AtomicInteger.value fields.
    comparisonState.registerForComparison(new DualValue(dualValue.fieldLocation.field(VALUE_FIELD_NAME), value1, value2));
  }

  private static void compareAtomicIntegerArray(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldAnAtomicIntegerArray()) {
      comparisonState.addDifference(dualValue, differentTypeErrorMessage(dualValue, "an AtomicIntegerArray"));
      return;
    }
    AtomicIntegerArray actual = (AtomicIntegerArray) dualValue.actual;
    AtomicIntegerArray expected = (AtomicIntegerArray) dualValue.expected;

    // both values in dualValue are arrays
    int actualArrayLength = actual.length();
    int expectedArrayLength = expected.length();
    if (actualArrayLength != expectedArrayLength) {
      comparisonState.addDifference(dualValue,
                                    format(DIFFERENT_SIZE_ERROR, "AtomicIntegerArrays", actualArrayLength, expectedArrayLength));
      // no need to inspect elements, arrays are not equal as they don't have the same size
      return;
    }
    // register each pair of actual/expected elements for recursive comparison
    FieldLocation arrayFieldLocation = dualValue.fieldLocation;
    for (int i = 0; i < actualArrayLength; i++) {
      Object actualElement = actual.get(i);
      Object expectedElement = expected.get(i);
      FieldLocation elementFieldLocation = arrayFieldLocation.field(format(ARRAY_FIELD_NAME + "[%d]", i));
      comparisonState.registerForComparison(new DualValue(elementFieldLocation, actualElement, expectedElement));
    }
  }

  private static void compareAtomicLong(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldAnAtomicLong()) {
      comparisonState.addDifference(dualValue, differentTypeErrorMessage(dualValue, "an AtomicLong"));
      return;
    }
    AtomicLong actual = (AtomicLong) dualValue.actual;
    AtomicLong expected = (AtomicLong) dualValue.expected;
    Object value1 = actual.get();
    Object value2 = expected.get();
    // we add VALUE_FIELD_NAME to the path since we register AtomicLong.value fields.
    comparisonState.registerForComparison(new DualValue(dualValue.fieldLocation.field(VALUE_FIELD_NAME), value1, value2));
  }

  private static void compareAtomicLongArray(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldAnAtomicLongArray()) {
      comparisonState.addDifference(dualValue, differentTypeErrorMessage(dualValue, "an AtomicLongArray"));
      return;
    }
    AtomicLongArray actual = (AtomicLongArray) dualValue.actual;
    AtomicLongArray expected = (AtomicLongArray) dualValue.expected;

    // both values in dualValue are arrays
    int actualArrayLength = actual.length();
    int expectedArrayLength = expected.length();
    if (actualArrayLength != expectedArrayLength) {
      comparisonState.addDifference(dualValue,
                                    format(DIFFERENT_SIZE_ERROR, "AtomicLongArrays", actualArrayLength, expectedArrayLength));
      // no need to inspect elements, arrays are not equal as they don't have the same size
      return;
    }
    // register each pair of actual/expected elements for recursive comparison
    FieldLocation arrayFieldLocation = dualValue.fieldLocation;
    for (int i = 0; i < actualArrayLength; i++) {
      Object actualElement = actual.get(i);
      Object expectedElement = expected.get(i);
      FieldLocation elementFieldLocation = arrayFieldLocation.field(format(ARRAY_FIELD_NAME + "[%d]", i));
      comparisonState.registerForComparison(new DualValue(elementFieldLocation, actualElement, expectedElement));
    }
  }

  private static void compareAtomicReferenceArray(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldAnAtomicReferenceArray()) {
      comparisonState.addDifference(dualValue, differentTypeErrorMessage(dualValue, "an AtomicReferenceArray"));
      return;
    }
    AtomicReferenceArray<?> actual = (AtomicReferenceArray<?>) dualValue.actual;
    AtomicReferenceArray<?> expected = (AtomicReferenceArray<?>) dualValue.expected;

    // both values in dualValue are arrays
    int actualArrayLength = actual.length();
    int expectedArrayLength = expected.length();
    if (actualArrayLength != expectedArrayLength) {
      comparisonState.addDifference(dualValue,
                                    format(DIFFERENT_SIZE_ERROR, "AtomicReferenceArrays", actualArrayLength,
                                           expectedArrayLength));
      // no need to inspect elements, arrays are not equal as they don't have the same size
      return;
    }
    // register each pair of actual/expected elements for recursive comparison
    FieldLocation arrayFieldLocation = dualValue.fieldLocation;
    for (int i = 0; i < actualArrayLength; i++) {
      Object actualElement = actual.get(i);
      Object expectedElement = expected.get(i);
      FieldLocation elementFieldLocation = arrayFieldLocation.field(format(ARRAY_FIELD_NAME + "[%d]", i));
      comparisonState.registerForComparison(new DualValue(elementFieldLocation, actualElement, expectedElement));
    }
  }

  private static void compareAtomicReference(DualValue dualValue, ComparisonState comparisonState) {
    if (!dualValue.isActualFieldAnAtomicReference()) {
      comparisonState.addDifference(dualValue, differentTypeErrorMessage(dualValue, "an AtomicReference"));
      return;
    }
    AtomicReference<?> actual = (AtomicReference<?>) dualValue.actual;
    AtomicReference<?> expected = (AtomicReference<?>) dualValue.expected;
    Object value1 = actual.get();
    Object value2 = expected.get();
    // we add VALUE_FIELD_NAME to the path since we register AtomicReference.value fields.
    comparisonState.registerForComparison(new DualValue(dualValue.fieldLocation.field(VALUE_FIELD_NAME), value1, value2));
  }

  /**
   * Determine if the passed in class has a non-Object.equals() method. This
   * method caches its results in static ConcurrentHashMap to benefit
   * execution performance.
   *
   * @param c Class to check.
   * @return true, if the passed in Class has a .equals() method somewhere
   *         between itself and just below Object in it's inheritance.
   */
  static boolean hasOverriddenEquals(Class<?> c) {
    if (customEquals.containsKey(c)) {
      return customEquals.get(c);
    }

    Class<?> origClass = c;
    while (!Object.class.equals(c)) {
      try {
        c.getDeclaredMethod("equals", Object.class);
        customEquals.put(origClass, true);
        return true;
      } catch (Exception ignored) {}
      c = c.getSuperclass();
    }
    customEquals.put(origClass, false);
    return false;
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private static boolean areDualValueEqual(DualValue dualValue,
                                           RecursiveComparisonConfiguration recursiveComparisonConfiguration) {
    final String fieldName = dualValue.getConcatenatedPath();
    final Object actualFieldValue = dualValue.actual;
    final Object expectedFieldValue = dualValue.expected;
    // check field comparators as they take precedence over type comparators
    Comparator fieldComparator = recursiveComparisonConfiguration.getComparatorForField(fieldName);
    if (fieldComparator != null) return areEqualUsingComparator(actualFieldValue, expectedFieldValue, fieldComparator, fieldName);
    // check if a type comparators exist for the field type
    Class fieldType = actualFieldValue != null ? actualFieldValue.getClass() : expectedFieldValue.getClass();
    Comparator typeComparator = recursiveComparisonConfiguration.getComparatorForType(fieldType);
    if (typeComparator != null) return areEqualUsingComparator(actualFieldValue, expectedFieldValue, typeComparator, fieldName);
    // default comparison using equals
    return deepEquals(actualFieldValue, expectedFieldValue);
  }

  private static boolean areEqualUsingComparator(final Object actual, final Object expected, Comparator<Object> comparator,
                                                 String fieldName) {
    try {
      return comparator.compare(actual, expected) == 0;
    } catch (ClassCastException e) {
      // this occurs when comparing field of different types, Person.id is an int and PersonDto.id is a long
      // TODO maybe we should let the exception bubble up?
      // assertion will fail with the current behavior and report other diff so it might be better to keep things this way
      System.out.printf("WARNING: Comparator was not suited to compare '%s' field values:%n" +
                        "- actual field value  : %s%n" +
                        "- expected field value: %s%n" +
                        "- comparator used     : %s%n",
                        fieldName, actual, expected, comparator);
      return false;
    }
  }

  private static ComparisonDifference expectedAndActualTypeDifference(Object actual, Object expected) {
    String additionalInformation = format("actual and expected are considered different since the comparison enforces strict type check and expected type %s is not a subtype of actual type %s",
                                          expected.getClass().getName(), actual.getClass().getName());
    return rootComparisonDifference(actual, expected, additionalInformation);
  }

  // TODO should be checking actual!
  private static boolean expectedTypeIsNotSubtypeOfActualType(DualValue dualField) {
    return expectedTypeIsNotSubtypeOfActualType(dualField.actual, dualField.expected);
  }

  private static boolean expectedTypeIsNotSubtypeOfActualType(Object actual, Object expected) {
    return !actual.getClass().isAssignableFrom(expected.getClass());
  }

  private static String describeOrderedCollectionTypes() {
    return Stream.of(DEFAULT_ORDERED_COLLECTION_TYPES)
                 .map(Class::getName)
                 .collect(joining(", ", "[", "]"));
  }
}
