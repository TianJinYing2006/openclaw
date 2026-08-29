package com.example.ykdsummer.fashion.wardrobe.web;

import com.example.ykdsummer.fashion.wardrobe.application.FashionCatalogService;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionProduct;
import com.example.ykdsummer.fashion.wardrobe.domain.FashionProductDraft;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriUtils;

/** Local administrator UI for a small curated catalog. Supplier import belongs in a later adapter. */
@Controller
@RequestMapping("/admin/catalog")
@ConditionalOnBean(FashionCatalogService.class)
public class FashionCatalogAdminController {
    private final FashionCatalogService catalog;

    public FashionCatalogAdminController(FashionCatalogService catalog) { this.catalog = catalog; }

    @GetMapping
    public String catalog(@RequestParam(required = false) String edit, Model model) {
        FashionProduct editing = edit == null || edit.isBlank() ? null : catalog.productByCode(edit).orElse(null);
        model.addAttribute("products", catalog.catalogProducts(100));
        model.addAttribute("editingProduct", editing);
        model.addAttribute("activePage", "catalog");
        return "admin/catalog";
    }

    @PostMapping("/products")
    public String saveProduct(
            @RequestParam String productCode,
            @RequestParam String title,
            @RequestParam(required = false) String brand,
            @RequestParam String categoryCode,
            @RequestParam String subCategoryCode,
            @RequestParam(required = false) String genderTarget,
            @RequestParam(required = false) String colorPrimary,
            @RequestParam(required = false) String secondaryColors,
            @RequestParam(required = false) String styleTags,
            @RequestParam(required = false) String seasonTags,
            @RequestParam(required = false) String occasionTags,
            @RequestParam(required = false) String material,
            @RequestParam(required = false) String fitCode,
            @RequestParam(required = false) String patternCode,
            @RequestParam BigDecimal price,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String availabilityStatus,
            @RequestParam(defaultValue = "0") int featuredRank,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String sourceProductId,
            @RequestParam(required = false) String sourceUrl,
            @RequestParam String textDescription,
            @RequestParam(required = false) String primaryImageUrl,
            RedirectAttributes redirect
    ) {
        try {
            FashionProduct saved = catalog.saveProduct(new FashionProductDraft(
                    productCode, title, brand, categoryCode, subCategoryCode, genderTarget, colorPrimary,
                    tags(secondaryColors), tags(styleTags), tags(seasonTags), tags(occasionTags), material, fitCode,
                    patternCode, price, currency, availabilityStatus, featuredRank, source, sourceProductId, sourceUrl,
                    textDescription, primaryImageUrl));
            redirect.addFlashAttribute("notice", "商品 " + saved.productCode() + " 已保存。再次使用同一商品编号提交会更新该商品。");
            return "redirect:/admin/catalog?edit=" + UriUtils.encode(saved.productCode(), StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", safeMessage(exception));
            return "redirect:/admin/catalog";
        }
    }

    @PostMapping("/products/{productId}/availability")
    public String updateAvailability(
            @PathVariable long productId,
            @RequestParam String availabilityStatus,
            RedirectAttributes redirect
    ) {
        try {
            if (!catalog.updateAvailability(productId, availabilityStatus)) {
                redirect.addFlashAttribute("error", "未找到该商品，状态未更新。");
            } else {
                redirect.addFlashAttribute("notice", "商品状态已更新。");
            }
        } catch (RuntimeException exception) {
            redirect.addFlashAttribute("error", safeMessage(exception));
        }
        return "redirect:/admin/catalog";
    }

    private static List<String> tags(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.replace('，', ',').split(","))
                .map(String::strip).filter(item -> !item.isBlank()).distinct().toList();
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "保存失败，请查看服务日志" : message;
    }
}
