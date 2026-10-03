/**
 * ANIMATED CART BUTTON (plan.md implementation)
 * Mini logistics sequence: Package enters -> Scanner scans -> Box closes lid -> Cart enters -> Package drops -> +1 -> Success
 */
(function () {
    'use strict';

    const CART_STATES = {
        IDLE: "idle",
        STARTING: "starting",
        PACKAGE_ENTERING: "package-entering",
        SCANNING: "scanning",
        CLOSING_LID: "closing-lid",
        CART_ENTERING: "cart-entering",
        MOVING_TO_CART: "moving-to-cart",
        CART_RECEIVING: "cart-receiving",
        SUCCESS: "success",
        ERROR: "error",
        RESETTING: "resetting"
    };

    const CART_TIMING = {
        hideDefault: 240,
        packageEnter: 850,
        scan: 750,
        closeFlaps: 280,
        sealTape: 200,
        cartEnter: 300,
        packageTravel: 800,
        packageDrop: 320,
        cartBounce: 260,
        badgePop: 320,
        sceneFade: 240,
        successHold: 1600,
        errorHold: 1500,
        reset: 280
    };

    class AnimatedCartButton {
        constructor(buttonEl, formEl) {
            this.button = buttonEl;
            this.form = formEl || buttonEl.closest('form');
            this.state = CART_STATES.IDLE;
            this.isAnimating = false;
            this.activeAnimations = [];

            // DOM elements inside button
            this.defaultEl = this.button.querySelector('.cart-default');
            this.sceneEl = this.button.querySelector('.cart-scene');
            this.packageEl = this.button.querySelector('.package');
            this.flapLeft = this.button.querySelector('.box-flap-left');
            this.flapRight = this.button.querySelector('.box-flap-right');
            this.boxTape = this.button.querySelector('.box-tape');
            this.scannerEl = this.button.querySelector('.scanner');
            this.ledLeft = this.button.querySelector('.scanner-led-left');
            this.ledRight = this.button.querySelector('.scanner-led-right');
            this.scannerLight = this.button.querySelector('.scanner-light');
            this.scannerLaser = this.button.querySelector('.scanner-laser-line');
            this.cartEl = this.button.querySelector('.shopping-cart');
            this.cartBadge = this.button.querySelector('.cart-badge');
            this.successEl = this.button.querySelector('.cart-success');
            this.successCircle = this.button.querySelector('.success-circle');
            this.successCheck = this.button.querySelector('.success-check');
            this.errorEl = this.button.querySelector('.cart-error');
            this.errorTextEl = this.errorEl ? this.errorEl.querySelector('span:last-child') : null;

            this.bindEvents();
        }

        bindEvents() {
            this.button.addEventListener('click', (e) => {
                e.preventDefault();
                this.handleClick();
            });

            this.button.addEventListener('keydown', (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    this.handleClick();
                }
            });
        }

        animateEl(el, keyframes, options) {
            if (!el) return Promise.resolve();
            const anim = el.animate(keyframes, options);
            this.activeAnimations.push(anim);
            return anim.finished.catch(() => { });
        }

        sleep(ms) {
            return new Promise(res => setTimeout(res, ms));
        }

        validateForm() {
            if (!this.form) return true;
            // Check size selection if present
            const sizeInputs = this.form.querySelectorAll('input[name="selectedSize"]');
            if (sizeInputs.length > 0) {
                const selected = Array.from(sizeInputs).some(input => input.checked);
                if (!selected) {
                    const sizeContainer = this.form.querySelector('.size-options') || this.form.querySelector('.size-selector');
                    if (sizeContainer) {
                        sizeContainer.scrollIntoView({ behavior: 'smooth', block: 'center' });
                        sizeContainer.style.outline = '2px solid #ef4444';
                        setTimeout(() => { sizeContainer.style.outline = 'none'; }, 2000);
                    }
                    alert('Vui lòng chọn kích cỡ giày trước khi thêm vào giỏ hàng.');
                    return false;
                }
            }
            return true;
        }

        async handleClick() {
            if (this.isAnimating || this.state !== CART_STATES.IDLE || this.button.disabled) {
                return;
            }

            if (!this.validateForm()) {
                return;
            }

            this.isAnimating = true;
            this.button.classList.add('is-animating');
            this.state = CART_STATES.STARTING;

            // Trigger API request in parallel
            const formData = this.form ? new FormData(this.form) : new FormData();
            const requestUrl = this.form ? this.form.action : window.location.href;

            const apiPromise = fetch(requestUrl, {
                method: 'POST',
                body: formData,
                headers: {
                    'Accept': 'application/json'
                }
            }).then(async res => {
                const data = await res.json().catch(() => ({}));
                if (!res.ok || data.success === false) {
                    throw new Error(data.message || 'Không thể thêm vào giỏ hàng.');
                }
                return data;
            });

            // Reduced Motion Fallback
            if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
                try {
                    const data = await apiPromise;
                    this.defaultEl.style.opacity = '0';
                    this.successEl.style.opacity = '1';
                    this.updateCartBadge(data.cartCount);
                    await this.sleep(1200);
                    this.resetToIdle();
                } catch (err) {
                    this.defaultEl.style.opacity = '0';
                    if (this.errorTextEl) this.errorTextEl.textContent = err.message || 'Thử lại sau';
                    this.errorEl.style.opacity = '1';
                    await this.sleep(1400);
                    this.resetToIdle();
                }
                return;
            }

            try {
                // Phase 1: Hide default label
                await this.animateEl(this.defaultEl, [
                    { opacity: 1, transform: "scale(1)" },
                    { opacity: 0, transform: "scale(0.95)" }
                ], { duration: CART_TIMING.hideDefault, easing: "ease-out", fill: "forwards" });

                this.sceneEl.style.opacity = "1";
                this.button.classList.add('is-running-conveyor');

                // Calculate dynamic positions with precision geometry
                const sceneRect = this.sceneEl ? this.sceneEl.getBoundingClientRect() : this.button.getBoundingClientRect();
                const btnWidth = sceneRect.width || this.button.clientWidth || 290;
                const pkgWidth = (this.packageEl && this.packageEl.offsetWidth) ? this.packageEl.offsetWidth : 32;

                // 1. Precise Scanner Center:
                // Use true visual bounding box to eliminate any transform: translateX(-50%) offset discrepancy
                const scannerRect = this.scannerEl ? this.scannerEl.getBoundingClientRect() : null;
                const scannerCenterX = (scannerRect && scannerRect.width > 0 && sceneRect.left !== undefined)
                    ? ((scannerRect.left + scannerRect.width / 2) - sceneRect.left)
                    : (btnWidth * 0.46);
                const scanX = Math.round(scannerCenterX - (pkgWidth / 2));

                // 2. Precise Shopping Cart Mouth Opening Center:
                // .shopping-cart is styled with right: 14px and width: 38px
                const cartWidth = 38;
                const cartLeft = btnWidth - 14 - cartWidth;
                // In cart SVG viewBox (54x50), basket top opening spans X=12 to X=44 with its mouth center at X=28 (28/54 ≈ 51.8%)
                const cartMouthCenter = cartLeft + Math.round((28 / 54) * cartWidth);

                // Approach position: package glides on conveyor to the mouth lip before tipping in
                const approachX = Math.round(cartMouthCenter - pkgWidth * 0.82);

                // Drop trajectory: package aligns dead-center over and drops directly into the basket mouth
                const dropX1 = Math.round(cartMouthCenter - (pkgWidth * 0.74) / 2);
                const dropX2 = Math.round(cartMouthCenter - (pkgWidth * 0.50) / 2);

                // Phase 2: Package enters from left to center
                this.state = CART_STATES.PACKAGE_ENTERING;
                await this.animateEl(this.packageEl, [
                    { transform: "translateX(-60px) scale(0.9)", opacity: 0 },
                    { transform: `translateX(${scanX}px) scale(1)`, opacity: 1 }
                ], { duration: CART_TIMING.packageEnter, easing: "cubic-bezier(.22,.8,.3,1)", fill: "forwards" });

                // Phase 3: Scanner activates and scans the package
                this.state = CART_STATES.SCANNING;
                if (this.ledLeft) this.ledLeft.classList.add('active');

                const scanLightAnim = this.scannerLight ? this.animateEl(this.scannerLight, [
                    { opacity: 0, transform: "translateX(-50%) scaleY(0.4)" },
                    { opacity: 0.9, transform: "translateX(-50%) scaleY(1)" },
                    { opacity: 0.6, transform: "translateX(-50%) scaleY(1)" },
                    { opacity: 0, transform: "translateX(-50%) scaleY(0.9)" }
                ], { duration: CART_TIMING.scan, easing: "ease-in-out" }) : Promise.resolve();

                const scanLaserAnim = this.scannerLaser ? this.animateEl(this.scannerLaser, [
                    { top: "0px", opacity: 0 },
                    { top: "6px", opacity: 1 },
                    { top: "28px", opacity: 1 },
                    { top: "34px", opacity: 0 }
                ], { duration: CART_TIMING.scan, easing: "ease-in-out" }) : Promise.resolve();

                // Wait for scan animation and API response
                const [_, __, apiResult] = await Promise.all([scanLightAnim, scanLaserAnim, apiPromise]);

                // MÁY QUÉT QUÉT XONG -> LED chuyển sang xanh
                if (this.ledLeft) this.ledLeft.classList.remove('active');
                if (this.ledRight) this.ledRight.classList.add('active-green');

                // ĐÓNG NẮP THÙNG (Flaps fold closed & tape seals)
                this.state = CART_STATES.CLOSING_LID;
                if (this.flapLeft && this.flapRight) {
                    const flapL = this.animateEl(this.flapLeft, [
                        { transform: "rotate(-24deg)" },
                        { transform: "rotate(0deg)" }
                    ], { duration: CART_TIMING.closeFlaps, easing: "cubic-bezier(.2,.8,.3,1)", fill: "forwards" });

                    const flapR = this.animateEl(this.flapRight, [
                        { transform: "rotate(24deg)" },
                        { transform: "rotate(0deg)" }
                    ], { duration: CART_TIMING.closeFlaps, easing: "cubic-bezier(.2,.8,.3,1)", fill: "forwards" });

                    await Promise.all([flapL, flapR]);

                    if (this.boxTape) {
                        await this.animateEl(this.boxTape, [
                            { opacity: 0, transform: "scaleX(0.4)" },
                            { opacity: 0.88, transform: "scaleX(1)" }
                        ], { duration: CART_TIMING.sealTape, easing: "ease-out", fill: "forwards" });
                    }
                    await this.sleep(100); // Thỏa mãn cảm giác vật lý sau khi đóng thùng
                }

                // Phase 4: Cart enters from right
                this.state = CART_STATES.CART_ENTERING;
                await this.animateEl(this.cartEl, [
                    { opacity: 0, transform: "translateX(30px) scale(0.85)" },
                    { opacity: 1, transform: "translateX(0px) scale(1)" }
                ], { duration: CART_TIMING.cartEnter, easing: "ease-out", fill: "forwards" });

                // Phase 5: Package advances from scanner position to cart entrance
                this.state = CART_STATES.MOVING_TO_CART;
                await this.animateEl(this.packageEl, [
                    { transform: `translateX(${scanX}px) translateY(0) scale(1)` },
                    { transform: `translateX(${approachX}px) translateY(0) scale(1)` }
                ], { duration: CART_TIMING.packageTravel, easing: "cubic-bezier(.22,.8,.3,1)", fill: "forwards" });

                // Phase 6: Package drops directly into the mouth of the cart basket + Cart Bounce + Badge +1
                this.state = CART_STATES.CART_RECEIVING;
                const dropAnim = this.animateEl(this.packageEl, [
                    { transform: `translateX(${approachX}px) translateY(0) rotate(0deg) scale(1)`, opacity: 1 },
                    { transform: `translateX(${dropX1}px) translateY(5px) rotate(14deg) scale(0.74)`, opacity: 0.95 },
                    { transform: `translateX(${dropX2}px) translateY(12px) rotate(10deg) scale(0.58)`, opacity: 0.65 },
                    { transform: `translateX(${dropX2}px) translateY(18px) rotate(6deg) scale(0.48)`, opacity: 0 }
                ], { duration: CART_TIMING.packageDrop, easing: "cubic-bezier(.34,0,.7,1)", fill: "forwards" });

                await this.sleep(130);
                const bounceAnim = this.animateEl(this.cartEl, [
                    { transform: "translateY(0px) scale(1)" },
                    { transform: "translateY(4px) scaleY(0.88) scaleX(1.05)" },
                    { transform: "translateY(-3px) scaleY(1.06) scaleX(0.97)" },
                    { transform: "translateY(0px) scale(1)" }
                ], { duration: CART_TIMING.cartBounce, easing: "cubic-bezier(.25,1.4,.5,1)" });

                await this.sleep(70);
                const badgeAnim = this.animateEl(this.cartBadge, [
                    { opacity: 0, transform: "scale(0) translateY(8px)" },
                    { opacity: 1, transform: "scale(1.3) translateY(-4px)" },
                    { opacity: 1, transform: "scale(1) translateY(0)" }
                ], { duration: CART_TIMING.badgePop, easing: "cubic-bezier(.34,1.56,.64,1)", fill: "forwards" });

                await Promise.all([dropAnim, bounceAnim, badgeAnim]);

                // Update navbar cart count
                if (apiResult && typeof apiResult.cartCount === 'number') {
                    this.updateCartBadge(apiResult.cartCount);
                }

                // Fade scene
                await this.sleep(150);
                await this.animateEl(this.sceneEl, [
                    { opacity: 1, transform: "scale(1)" },
                    { opacity: 0, transform: "scale(0.98)" }
                ], { duration: CART_TIMING.sceneFade, easing: "ease-out", fill: "forwards" });

                this.button.classList.remove('is-running-conveyor');

                // Phase 7: Success State (SVG Checkmark draw + Text)
                this.state = CART_STATES.SUCCESS;
                this.button.classList.add('is-success');
                this.successEl.style.opacity = "1";
                this.successEl.style.transform = "translateY(0)";

                if (this.successCircle && this.successCheck) {
                    this.animateEl(this.successCircle, [
                        { strokeDashoffset: "100" },
                        { strokeDashoffset: "0" }
                    ], { duration: 320, easing: "ease-out", fill: "forwards" });

                    this.animateEl(this.successCheck, [
                        { strokeDashoffset: "30" },
                        { strokeDashoffset: "0" }
                    ], { duration: 250, easing: "ease-out", fill: "forwards", delay: 160 });
                }

                await this.sleep(CART_TIMING.successHold);
                await this.resetToIdle();

            } catch (error) {
                console.warn('Add to cart failed:', error);
                this.state = CART_STATES.ERROR;

                if (this.ledLeft) this.ledLeft.classList.remove('active');
                if (this.ledRight) this.ledRight.classList.add('active-red');

                await this.sleep(200);
                await this.animateEl(this.sceneEl, [
                    { opacity: 1 },
                    { opacity: 0 }
                ], { duration: 200, fill: "forwards" });

                this.button.classList.remove('is-running-conveyor');
                if (this.errorTextEl && error.message) {
                    this.errorTextEl.textContent = error.message;
                }
                this.errorEl.style.opacity = "1";
                this.errorEl.style.transform = "translateY(0)";

                await this.sleep(CART_TIMING.errorHold);
                await this.resetToIdle();
            } finally {
                this.isAnimating = false;
                this.button.classList.remove('is-animating');
            }
        }

        updateCartBadge(newCount) {
            const badge = document.querySelector('.cart-count-badge');
            if (badge) {
                badge.textContent = newCount;
                badge.animate([
                    { transform: 'scale(1)' },
                    { transform: 'scale(1.4)' },
                    { transform: 'scale(1)' }
                ], { duration: 300, easing: 'cubic-bezier(.34,1.56,.64,1)' });
            }
        }

        async resetToIdle() {
            this.state = CART_STATES.RESETTING;
            this.button.classList.remove('is-success');

            this.activeAnimations.forEach(a => {
                try { a.cancel(); } catch (e) { }
            });
            this.activeAnimations = [];

            this.sceneEl.style.opacity = "0";
            this.sceneEl.style.transform = "none";
            this.successEl.style.opacity = "0";
            this.successEl.style.transform = "translateY(4px)";
            this.errorEl.style.opacity = "0";
            this.errorEl.style.transform = "translateY(4px)";

            if (this.packageEl) {
                this.packageEl.style.transform = "translateX(-60px) scale(0.9)";
                this.packageEl.style.opacity = "0";
            }
            if (this.cartEl) {
                this.cartEl.style.transform = "translateX(30px) scale(0.85)";
                this.cartEl.style.opacity = "0";
            }
            if (this.cartBadge) {
                this.cartBadge.style.transform = "scale(0)";
                this.cartBadge.style.opacity = "0";
            }
            if (this.flapLeft) this.flapLeft.style.transform = "rotate(-24deg)";
            if (this.flapRight) this.flapRight.style.transform = "rotate(24deg)";
            if (this.boxTape) this.boxTape.style.opacity = "0";
            if (this.ledLeft) this.ledLeft.className = "scanner-led scanner-led-left";
            if (this.ledRight) this.ledRight.className = "scanner-led scanner-led-right";

            if (this.successCircle) this.successCircle.style.strokeDashoffset = "100";
            if (this.successCheck) this.successCheck.style.strokeDashoffset = "30";

            await this.animateEl(this.defaultEl, [
                { opacity: 0, transform: "scale(0.95)" },
                { opacity: 1, transform: "scale(1)" }
            ], { duration: CART_TIMING.reset, easing: "ease-out", fill: "forwards" });

            this.state = CART_STATES.IDLE;
            this.isAnimating = false;
        }
    }

    // Auto-initialize all .cart-button elements
    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('.cart-button').forEach(btn => {
            new AnimatedCartButton(btn);
        });
    });

    window.AnimatedCartButton = AnimatedCartButton;
})();
