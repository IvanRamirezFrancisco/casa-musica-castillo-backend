-- TEST ONLY: legacy pre-Flyway schema. Never included by production locations.
-- Exported with the project's Hibernate 6.3.1 from entity/enums at ce73f95,
-- the commit introducing V4, with Spring's CamelCaseToUnderscores naming strategy.
-- Full mapped tables/keys are retained, not placeholder tables to bypass V4.
-- inventory_prediction_config was managed outside JPA/Flyway; its complete JDBC
-- contract is recorded from InventoryPredictionService and V13/V23.
-- V4..V51 run unchanged AFTER this baseline. No baselineVersion/ignore/skip flags.
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='spring_app') THEN
  CREATE ROLE spring_app NOLOGIN;
 END IF;
END $$;
create table active_sessions (id bigserial not null, created_at timestamp(6) not null, expires_at timestamp(6) not null, ip_address varchar(45), jwt_token_id varchar(255) not null, last_activity timestamp(6) not null, revoked boolean not null, user_agent TEXT, user_id bigint not null, primary key (id));
create table addresses (id bigserial not null, active boolean not null, address_type varchar(20) not null check (address_type in ('BILLING','SHIPPING','BOTH')), apartment varchar(50), city varchar(100) not null, created_at timestamp(6) not null, is_default boolean not null, neighborhood varchar(100), postal_code varchar(20) not null, recipient_name varchar(200), recipient_phone varchar(20), reference varchar(300), state varchar(100) not null, street varchar(200) not null, updated_at timestamp(6), country_id integer not null, user_id bigint not null, primary key (id));
create table audit_logs (id bigserial not null, action varchar(50) not null, created_at timestamp(6) not null, details TEXT, entity_id bigint, entity_type varchar(50) not null, error_message varchar(500), event_description TEXT not null, event_type varchar(100) not null, ip_address varchar(45), is_success boolean not null, new_values json, old_values json, performed_by varchar(100) not null, performed_by_user_id bigint, resource_affected varchar(200), severity varchar(20), status varchar(50), user_agent TEXT, primary key (id));
create table backup_codes (id bigserial not null, code_hash varchar(255) not null, created_at timestamp(6) not null, used boolean not null, used_at timestamp(6), user_id bigint not null, primary key (id));
create table backup_logs (id bigserial not null, created_at timestamp(6) not null, error_message TEXT, execution_time_ms bigint, file_path varchar(500), file_size_bytes bigint, filename varchar(255) not null, is_deleted boolean not null, status varchar(50) not null check (status in ('PENDING','COMPLETED','FAILED')), triggered_by varchar(100), primary key (id));
create table brands (id bigserial not null, active boolean not null, country_origin varchar(100), created_at timestamp(6) not null, description TEXT, logo_url varchar(255), name varchar(100) not null unique, product_count bigint, updated_at timestamp(6), website_url varchar(255), primary key (id));
create table cart_items (id bigserial not null, added_at timestamp(6) not null, quantity integer not null, subtotal numeric(10,2) not null, unit_price numeric(10,2) not null, updated_at timestamp(6), cart_id bigint not null, product_id bigint not null, primary key (id));
create table categories (id bigserial not null, active boolean not null, created_at timestamp(6) not null, description TEXT, image_url varchar(255), name varchar(100) not null unique, updated_at timestamp(6), parent_id bigint, primary key (id));
create table countries (id serial not null, active boolean not null, code varchar(2) not null unique, created_at timestamp(6) not null, name varchar(100) not null, phone_prefix varchar(5), primary key (id));
create table coupon_applicable_categories (coupon_id bigint not null, category_id bigint not null, primary key (coupon_id, category_id));
create table coupon_applicable_products (coupon_id bigint not null, product_id bigint not null, primary key (coupon_id, product_id));
create table coupon_usage (id bigserial not null, discount_applied numeric(10,2) not null, order_total numeric(10,2), used_at timestamp(6) not null, cart_id bigint, coupon_id bigint not null, order_id bigint, user_id bigint not null, primary key (id));
create table coupons (id bigserial not null, code varchar(50) not null unique, created_at timestamp(6) not null, description varchar(200), discount_type varchar(20) not null, discount_value numeric(10,2) not null, first_purchase_only boolean not null, is_active boolean not null, maximum_discount numeric(10,2), minimum_purchase numeric(10,2), times_used integer not null, updated_at timestamp(6), usage_limit integer, usage_limit_per_user integer, valid_from timestamp(6), valid_until timestamp(6), primary key (id));
create table login_attempts (id bigserial not null, attempt_time timestamp(6) not null, email varchar(100) not null, failure_reason varchar(255), ip_address varchar(45), successful boolean not null, primary key (id));
create table order_items (id bigserial not null, created_at timestamp(6) not null, discount numeric(10,2), product_name varchar(200) not null, product_sku varchar(50) not null, quantity integer not null, subtotal numeric(10,2) not null, unit_price numeric(10,2) not null, order_id bigint not null, product_id bigint not null, primary key (id));
create table orders (id bigserial not null, billing_address TEXT, cancellation_reason TEXT, cancelled_at timestamp(6), created_at timestamp(6) not null, customer_notes TEXT, delivered_at timestamp(6), discount numeric(10,2), notes TEXT, order_number varchar(50) not null unique, payment_method varchar(30) check (payment_method in ('CREDIT_CARD','DEBIT_CARD','PAYPAL','BANK_TRANSFER','CASH_ON_DELIVERY','MERCADO_PAGO','STRIPE')), payment_status varchar(20) not null check (payment_status in ('PENDING','PAID','FAILED','REFUNDED','PARTIALLY_REFUNDED')), shipped_at timestamp(6), shipping numeric(10,2), shipping_address TEXT, shipping_status varchar(20) not null check (shipping_status in ('PENDING','PREPARING','SHIPPED','IN_TRANSIT','DELIVERED','RETURNED')), status varchar(20) not null check (status in ('PENDING','CONFIRMED','PROCESSING','COMPLETED','CANCELLED')), subtotal numeric(10,2) not null, tax numeric(10,2), total numeric(10,2) not null, tracking_number varchar(100), transaction_id varchar(100), updated_at timestamp(6), user_id bigint not null, primary key (id));
create table password_recovery_attempts (id bigserial not null, attempt_count integer not null, is_blocked boolean not null, blocked_until timestamp(6), created_at timestamp(6) not null, email varchar(255) not null, ip_address varchar(45) not null, last_attempt timestamp(6) not null, updated_at timestamp(6), user_agent varchar(500), primary key (id));
create table password_reset_tokens (id bigserial not null, attempts integer not null, created_at timestamp(6) not null, expiry_date timestamp(6) not null, ip_address varchar(45), token varchar(255) not null unique, used boolean not null, used_at timestamp(6), user_agent varchar(500), user_id bigint not null, primary key (id));
create table permissions (id bigserial not null, category varchar(50), created_at timestamp(6) not null, description varchar(255), name varchar(100) not null unique, updated_at timestamp(6), primary key (id));
create table product_attributes (id bigserial not null, attribute_name varchar(100) not null, attribute_value varchar(255) not null, display_order integer not null, product_id bigint not null, primary key (id));
create table product_images (id bigserial not null, alt_text varchar(200), created_at timestamp(6) not null, display_order integer not null, image_url varchar(500) not null, product_id bigint not null, primary key (id));
create table product_price_history (id bigserial not null, created_at timestamp(6) not null, effective_from timestamp(6) not null, effective_to timestamp(6), new_discount_price numeric(10,2), new_price numeric(10,2) not null, old_discount_price numeric(10,2), old_price numeric(10,2), reason varchar(200), changed_by bigint, product_id bigint not null, primary key (id));
create table product_reviews (id bigserial not null, comment TEXT not null, created_at timestamp(6) not null, helpful_count integer not null, not_helpful_count integer not null, rating integer not null, seller_response TEXT, seller_response_at timestamp(6), status varchar(20) not null, title varchar(200), verified_purchase boolean not null, order_id bigint, product_id bigint not null, user_id bigint not null, primary key (id));
create table products (id bigserial not null, active boolean not null, average_rating numeric(3,2) not null, created_at timestamp(6) not null, description TEXT, detailed_description TEXT, dimensions varchar(100), discount_price numeric(10,2), featured boolean not null, five_star_count integer not null, four_star_count integer not null, image_url varchar(500), model varchar(100), name varchar(200) not null, one_star_count integer not null, price numeric(10,2) not null, review_count integer not null, sales_count bigint, sku varchar(50) not null unique, stock integer not null, three_star_count integer not null, two_star_count integer not null, updated_at timestamp(6), views bigint, weight float(53), brand_id bigint, category_id bigint, primary key (id));
create table refresh_tokens (id bigserial not null, created_at timestamp(6) not null, expiry_date timestamp(6) not null, token varchar(255) not null unique, user_id bigint not null, primary key (id));
create table review_helpfulness (id bigserial not null, created_at timestamp(6) not null, is_helpful boolean not null, review_id bigint not null, user_id bigint not null, primary key (id), constraint uk_review_user unique (review_id, user_id));
create table role_permissions (role_id bigint not null, permission_id bigint not null, primary key (role_id, permission_id));
create table roles (id bigserial not null, created_at timestamp(6) not null, description varchar(255), name varchar(60) not null unique, updated_at timestamp(6), primary key (id));
create table security_settings (id bigserial not null, category varchar(50), data_type varchar(20), description TEXT, is_public boolean, max_value varchar(100), min_value varchar(100), setting_key varchar(100) not null unique, setting_value TEXT, updated_at timestamp(6), updated_by bigint, primary key (id));
create table shopping_carts (id bigserial not null, coupon_code varchar(50), created_at timestamp(6) not null, discount numeric(10,2), expires_at timestamp(6), session_id varchar(100), status varchar(20) not null, subtotal numeric(10,2) not null, tax numeric(10,2) not null, total numeric(10,2) not null, updated_at timestamp(6), user_id bigint, primary key (id));
create table two_factor_tokens (id bigserial not null, created_at timestamp(6) not null, expiry_date timestamp(6) not null, token varchar(10) not null, token_type varchar(20), used boolean not null, used_at timestamp(6), user_id bigint not null, primary key (id));
create table user_roles (user_id bigint not null, role_id bigint not null, primary key (user_id, role_id));
create table users (id bigserial not null, account_non_expired boolean not null, account_non_locked boolean not null, backup_codes_enabled boolean, created_at timestamp(6) not null, credentials_non_expired boolean not null, email varchar(255) not null unique, email_enabled boolean, enabled boolean not null, first_name varchar(255) not null, google_auth_enabled boolean, google_auth_secret varchar(255), is_customer boolean not null, last_name varchar(255) not null, password varchar(255) not null, phone varchar(20), total_orders integer, total_spent numeric(15,2), two_factor_enabled boolean not null, two_factor_secret varchar(32), two_factor_type varchar(50) check (two_factor_type in ('GOOGLE_AUTHENTICATOR','EMAIL')), updated_at timestamp(6), username varchar(30) not null unique, primary key (id));
create table verification_tokens (id bigserial not null, created_at timestamp(6) not null, expiry_date timestamp(6) not null, token varchar(255) not null unique, token_type varchar(255) not null check (token_type in ('EMAIL_VERIFICATION','PASSWORD_RESET','TWO_FACTOR_AUTH','ACCOUNT_RECOVERY')), used boolean not null, user_id bigint not null, primary key (id));
create table wishlists (id bigserial not null, added_at timestamp(6) not null, notes TEXT, notified_back_in_stock boolean not null, notified_discount boolean not null, price_when_added numeric(10,2), priority integer not null, updated_at timestamp(6), product_id bigint not null, user_id bigint not null, primary key (id), constraint uk_wishlist_user_product unique (user_id, product_id));
create index idx_addresses_user on addresses (user_id, active);
create index idx_addresses_default on addresses (user_id, is_default);
create index idx_addresses_country on addresses (country_id);
create index idx_audit_action on audit_logs (action);
create index idx_audit_entity_type on audit_logs (entity_type);
create index idx_audit_performed_by on audit_logs (performed_by);
create index idx_audit_created_at on audit_logs (created_at);
create index idx_user_id on backup_codes (user_id);
create index idx_code_hash on backup_codes (code_hash);
create index idx_used on backup_codes (used);
create index idx_user_unused on backup_codes (user_id, used);
create index idx_cartitem_cart on cart_items (cart_id);
create index idx_cartitem_product on cart_items (product_id);
create index idx_usage_coupon on coupon_usage (coupon_id);
create index idx_usage_user on coupon_usage (user_id);
create index idx_usage_order on coupon_usage (order_id);
create index idx_coupon_type on coupons (discount_type);
create index idx_coupon_active on coupons (is_active);
create index idx_coupon_dates on coupons (valid_from, valid_until);
create index idx_order_user on orders (user_id);
create index idx_order_status on orders (status);
create index idx_order_date on orders (created_at);
create index idx_payment_status on orders (payment_status);
create index idx_order_number on orders (order_number);
create index idx_product_attrs_product on product_attributes (product_id);
create index idx_product_attrs_name on product_attributes (attribute_name);
create index idx_price_history_product on product_price_history (product_id, effective_from);
create index idx_price_history_dates on product_price_history (effective_from, effective_to);
create index idx_price_history_user on product_price_history (changed_by);
create index idx_review_product on product_reviews (product_id);
create index idx_review_user on product_reviews (user_id);
create index idx_review_rating on product_reviews (rating);
create index idx_review_verified on product_reviews (verified_purchase);
create index idx_review_status on product_reviews (status);
create index idx_helpfulness_review on review_helpfulness (review_id);
create index idx_helpfulness_user on review_helpfulness (user_id);
create index idx_cart_user on shopping_carts (user_id);
create index idx_cart_session on shopping_carts (session_id);
create index idx_cart_status on shopping_carts (status);
create index idx_cart_expires on shopping_carts (expires_at);
create index idx_wishlist_user on wishlists (user_id);
create index idx_wishlist_product on wishlists (product_id);
create index idx_wishlist_priority on wishlists (priority);
create index idx_wishlist_notified on wishlists (notified_back_in_stock);
alter table if exists active_sessions add constraint FKi160hxodalwko5kwgd80oeyq8 foreign key (user_id) references users;
alter table if exists addresses add constraint FKn3sth7s3kur1rafwbbrqqnswt foreign key (country_id) references countries;
alter table if exists addresses add constraint FK1fa36y2oqhao3wgg2rw1pi459 foreign key (user_id) references users;
alter table if exists backup_codes add constraint FK4uqupj7j6eldpff5ycw8vnpm6 foreign key (user_id) references users;
alter table if exists cart_items add constraint FKojy3ibx281qswho045bw4q0da foreign key (cart_id) references shopping_carts;
alter table if exists cart_items add constraint FK1re40cjegsfvw58xrkdp6bac6 foreign key (product_id) references products;
alter table if exists categories add constraint FKsaok720gsu4u2wrgbk10b5n8d foreign key (parent_id) references categories;
alter table if exists coupon_applicable_categories add constraint FKo92tlg2nbndty2e263q5qrquk foreign key (category_id) references categories;
alter table if exists coupon_applicable_categories add constraint FK6iulmrpitw08078d7rhn0xill foreign key (coupon_id) references coupons;
alter table if exists coupon_applicable_products add constraint FKmf8mlv5gys68wv5ecc1coaxc3 foreign key (product_id) references products;
alter table if exists coupon_applicable_products add constraint FKh7wu7u49aufrv5ri1j65igx25 foreign key (coupon_id) references coupons;
alter table if exists coupon_usage add constraint FKfnx9irjn2kcacsxb0mwf8dyry foreign key (cart_id) references shopping_carts;
alter table if exists coupon_usage add constraint FKof9h3rw1vu403j5dbehr45ts2 foreign key (coupon_id) references coupons;
alter table if exists coupon_usage add constraint FKejqwoh58dj8l7apjqo75crp88 foreign key (order_id) references orders;
alter table if exists coupon_usage add constraint FKcsx5pbxjnp6jvuveb3lxyklhe foreign key (user_id) references users;
alter table if exists order_items add constraint FKbioxgbv59vetrxe0ejfubep1w foreign key (order_id) references orders;
alter table if exists order_items add constraint FKocimc7dtr037rh4ls4l95nlfi foreign key (product_id) references products;
alter table if exists orders add constraint FK32ql8ubntj5uh44ph9659tiih foreign key (user_id) references users;
alter table if exists password_reset_tokens add constraint FKk3ndxg5xp6v7wd4gjyusp15gq foreign key (user_id) references users;
alter table if exists product_attributes add constraint FKcex46yvx4g18b2pn09p79h1mc foreign key (product_id) references products;
alter table if exists product_images add constraint FKqnq71xsohugpqwf3c9gxmsuy foreign key (product_id) references products;
alter table if exists product_price_history add constraint FKmk25m9jk3i8puaf2sxqadcd8t foreign key (changed_by) references users;
alter table if exists product_price_history add constraint FKcc0pjma67c06t2cvsoyesswht foreign key (product_id) references products;
alter table if exists product_reviews add constraint FKkpu8o8nqopc4lcqcfnnlpq5vg foreign key (order_id) references orders;
alter table if exists product_reviews add constraint FK35kxxqe2g9r4mww80w9e3tnw9 foreign key (product_id) references products;
alter table if exists product_reviews add constraint FK58i39bhws2hss3tbcvdmrm60f foreign key (user_id) references users;
alter table if exists products add constraint FKa3a4mpsfdf4d2y6r8ra3sc8mv foreign key (brand_id) references brands;
alter table if exists products add constraint FKog2rp4qthbtt2lfyhfo32lsw9 foreign key (category_id) references categories;
alter table if exists refresh_tokens add constraint FK1lih5y2npsf8u5o3vhdb9y0os foreign key (user_id) references users;
alter table if exists review_helpfulness add constraint FKlce2k6na87ixylief1lbhfhtt foreign key (review_id) references product_reviews;
alter table if exists review_helpfulness add constraint FKjhy7h7evqiujr779egbex6kam foreign key (user_id) references users;
alter table if exists role_permissions add constraint FKegdk29eiy7mdtefy5c7eirr6e foreign key (permission_id) references permissions;
alter table if exists role_permissions add constraint FKn5fotdgk8d1xvo8nav9uv3muc foreign key (role_id) references roles;
alter table if exists security_settings add constraint fk_security_settings_updated_by foreign key (updated_by) references users;
alter table if exists shopping_carts add constraint FK3iw2988ea60alsp0gnvvyt744 foreign key (user_id) references users;
alter table if exists two_factor_tokens add constraint FKcj0n9w4lm6k2jd14slxvd99q8 foreign key (user_id) references users;
alter table if exists user_roles add constraint FKh8ciramu9cc9q3qcqiv4ue8a6 foreign key (role_id) references roles;
alter table if exists user_roles add constraint FKhfh9dx7w3ubf1co1vdev94g3f foreign key (user_id) references users;
alter table if exists verification_tokens add constraint FK54y8mqsnq1rtyf581sfmrbp4f foreign key (user_id) references users;
alter table if exists wishlists add constraint FKl7ao98u2bm8nijc1rv4jobcrx foreign key (product_id) references products;
alter table if exists wishlists add constraint FK330pyw2el06fn5g28ypyljt16 foreign key (user_id) references users;

CREATE TABLE inventory_prediction_config (
 id BIGSERIAL PRIMARY KEY,
 section_name VARCHAR(100) NOT NULL,
 section_key VARCHAR(50) NOT NULL UNIQUE,
 category_id BIGINT REFERENCES categories(id),
 stock_reference NUMERIC NOT NULL CHECK (stock_reference >= 0),
 reference_date DATE NOT NULL,
 critical_level NUMERIC NOT NULL CHECK (critical_level >= 0),
 alert_threshold_days INTEGER NOT NULL CHECK (alert_threshold_days >= 0)
);

-- Legacy column retained in the database after its Java field was removed;
-- V5 explicitly converts it. It must exist in the pre-Flyway baseline.
ALTER TABLE products ADD COLUMN secondary_images TEXT;

-- V6 converts these legacy columns, later superseded by join tables in the Java model.
ALTER TABLE coupons ADD COLUMN applicable_categories TEXT;
ALTER TABLE coupons ADD COLUMN applicable_products TEXT;
