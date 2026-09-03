package com.bizplay.builder.featurespec;

import java.util.List;

/** AI와 Builder 사이의 기능명세서 구조화 계약. */
public record FeatureSpecContent(
        String title,
        Overview overview,
        List<TextItem> preconditions,
        List<FunctionItem> functions,
        List<FieldItem> fields,
        List<RuleItem> businessRules,
        List<RuleItem> permissionRules,
        List<MessageItem> messages,
        List<TransitionItem> transitions,
        List<IntegrationItem> integrations) {

    public record Overview(String purpose, String scope, List<String> evidenceIds) { }
    public record TextItem(String text, List<String> evidenceIds) { }
    public record FunctionItem(String name, String trigger, String precondition,
                               String processing, String result, List<String> evidenceIds) { }
    public record FieldItem(String name, String type, String required, String inputRule,
                            String description, List<String> evidenceIds) { }
    public record RuleItem(String title, String description, List<String> evidenceIds) { }
    public record MessageItem(String situation, String message, List<String> evidenceIds) { }
    public record TransitionItem(String action, String targetScreenId, String result,
                                 List<String> evidenceIds) { }
    public record IntegrationItem(String name, String direction, String data, String condition,
                                  List<String> evidenceIds) { }
}
