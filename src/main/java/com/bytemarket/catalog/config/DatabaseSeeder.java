package com.bytemarket.catalog.config;

import com.bytemarket.catalog.model.*;
import com.bytemarket.catalog.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DatabaseSeeder implements CommandLineRunner {

    @Autowired
    private ICategoryRepository categoryRepository;

    @Autowired
    private ISubcategoryRepository subcategoryRepository;

    @Autowired
    private IProductRepository productRepository;

    @Autowired
    private IProductImageRepository productImageRepository;

    @Autowired
    private IBannerRepository bannerRepository;

    @Override
    public void run(String... args) throws Exception {
        if (categoryRepository.count() == 0) {
            System.out.println("🌱 Iniciando seed de ByteMarket...");

            // 1. Categories
            String[] catNames = {
                "Pantallas y Displays", 
                "Baterías y Cargadores", 
                "Carcasas y Protectores", 
                "Accesorios y Cables"
            };

            List<Category> categories = new ArrayList<>();
            for (int i = 0; i < catNames.length; i++) {
                Category c = new Category();
                c.setName(catNames[i]);
                c.setSlug(catNames[i].toLowerCase().replace(" y ", "-").replace(" ", "-").replace("í", "i"));
                c.setSortOrder(i);
                c.setIsActive(1);
                c = categoryRepository.save(c);
                categories.add(c);

                // Subcategories
                Subcategory s1 = new Subcategory();
                s1.setCategory(c);
                s1.setName(catNames[i] + " Premium");
                s1.setSlug(c.getSlug() + "-premium");
                s1.setSortOrder(0);
                s1.setActive(true);
                subcategoryRepository.save(s1);

                Subcategory s2 = new Subcategory();
                s2.setCategory(c);
                s2.setName(catNames[i] + " Estándar");
                s2.setSlug(c.getSlug() + "-estandar");
                s2.setSortOrder(1);
                s2.setActive(true);
                subcategoryRepository.save(s2);
            }

            // 2. Banners
            Banners b1 = new Banners();
            b1.setImageUrl("https://picsum.photos/seed/bytemarket-banner-1/1680/720");
            b1.setLinkUrl("/productos");
            
            b1.setTitle("La pieza correcta, a la primera");
            b1.setSubtitle("Catálogo ordenado por modelo, con el stock tal como está.");
            
            
            b1.setSortOrder(0);
            b1.setIsActive(1);
            b1.setEyebrow("Repuestos con garantía");
            b1.setCtaLabel("Ver catálogo");
            b1.setAlign("left");
            bannerRepository.save(b1);

            Banners b2 = new Banners();
            b2.setImageUrl("https://picsum.photos/seed/bytemarket-banner-2/1680/720");
            b2.setLinkUrl("/productos?nuevoLanzamiento=1");
            
            b2.setTitle("Lo último que entró al almacén");
            b2.setSubtitle("Pantallas, baterías y accesorios de los equipos que más se reparan.");
            
            
            b2.setSortOrder(1);
            b2.setIsActive(1);
            b2.setEyebrow("Novedades");
            b2.setCtaLabel("Ver novedades");
            b2.setAlign("left");
            bannerRepository.save(b2);

            // 3. Products
            String[] productNames = {
                "Pantalla LCD iPhone 13 Original",
                "Pantalla OLED Samsung S22 Premium",
                "Batería iPhone 12 3110mAh",
                "Batería Samsung A52 4500mAh",
                "Cargador Rápido 65W USB-C",
                "Cable USB-C a Lightning 1m Trenzado",
                "Carcasa iPhone 14 Pro Antigolpes",
                "Carcasa Samsung S23 Ultra Transparente",
                "Vidrio Templado iPhone 15 Full Cover",
                "Kit Herramientas Reparación Celular 20 piezas",
                "Auriculares Bluetooth 5.0 In-Ear",
                "Cargador Inalámbrico 15W MagSafe Compatible"
            };

            List<Subcategory> allSubs = subcategoryRepository.findAll();

            for (int i = 0; i < productNames.length; i++) {
                String name = productNames[i];
                Product p = new Product();
                p.setName(name);
                p.setSlug(name.toLowerCase().replace(" ", "-").replace("í", "i").replace("ó", "o").replace("á", "a"));
                p.setDescription(name + ": repuesto o accesorio de alta calidad, compatible garantizado y probado. Ideal para técnicos y usuarios finales.");
                p.setPrice(Math.round((Math.random() * 180 + 15) * 100.0) / 100.0);
                p.setStock((int) (Math.random() * 60) + 10);
                p.setIsFeatured(i < 4 ? 1 : 0);
                p.setNuevoLanzamiento(i % 3 == 0 ? 1 : 0);
                p.setIsActive(1);
                
                Category cat = categories.get(i % categories.size());
                p.setCategory(cat);
                
                // Find a subcategory for this category
                Subcategory sub = allSubs.stream()
                        .filter(s -> s.getCategory().getId().equals(cat.getId()))
                        .findFirst().orElse(null);
                        
                if (sub != null) {
                    p.setSubcategoryId(sub.getId());
                }

                p = productRepository.save(p);

                // Images
                for (int j = 0; j < 3; j++) {
                    ProductImage img = new ProductImage();
                    img.setProduct(p);
                    img.setUrl("https://picsum.photos/seed/bytemarket-prod-" + p.getId() + "-" + j + "/600/600");
                    img.setSortOrder(j);
                    img.setIsPrimary(j == 0 ? 1 : 0);
                    productImageRepository.save(img);
                }
            }

            System.out.println("✅ Seed completado en ByteMarket.");
        }
    }
}
