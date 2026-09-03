package com.bizplay.builder.design;

import java.util.ArrayList;
import java.util.List;

/** 컴포넌트 카드 하나를 Builder 확정본으로 저장하는 폼이다. */
public class DesignSystemComponentForm {

    private int version;
    private String label;
    private String category;
    private boolean hidden;
    private int displayOrder;
    private List<VariantForm> variants = new ArrayList<>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public List<VariantForm> getVariants() {
        return variants;
    }

    public void setVariants(List<VariantForm> variants) {
        this.variants = variants == null ? new ArrayList<>() : variants;
    }

    DesignSystemCurationService.ComponentInput input(String componentId) {
        return new DesignSystemCurationService.ComponentInput(componentId, label, category, hidden, displayOrder,
                variants.stream().map(variant -> new DesignSystemCurationService.VariantInput(
                        variant.getId(), variant.getLabel(), variant.isHidden())).toList());
    }

    public static class VariantForm {
        private String id;
        private String label;
        private boolean hidden;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public boolean isHidden() {
            return hidden;
        }

        public void setHidden(boolean hidden) {
            this.hidden = hidden;
        }
    }
}
