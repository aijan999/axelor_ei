/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2022 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.account.service;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.Irrecoverable;
import com.axelor.apps.account.db.IrrecoverableCustomerLine;
import com.axelor.apps.account.db.IrrecoverableInvoiceLine;
import com.axelor.apps.account.db.IrrecoverablePaymentScheduleLineLine;
import com.axelor.apps.account.db.IrrecoverableReportLine;
import com.axelor.apps.account.db.ManagementObject;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.PaymentSchedule;
import com.axelor.apps.account.db.PaymentScheduleLine;
import com.axelor.apps.account.db.Reconcile;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.IrrecoverableCustomerLineRepository;
import com.axelor.apps.account.db.repo.IrrecoverableRepository;
import com.axelor.apps.account.db.repo.ManagementObjectRepository;
import com.axelor.apps.account.db.repo.MoveLineRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.db.repo.PaymentScheduleLineRepository;
import com.axelor.apps.account.db.repo.PaymentScheduleRepository;
import com.axelor.apps.account.exception.IExceptionMessage;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveToolService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.base.service.tax.TaxService;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.ExceptionOriginRepository;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.exception.service.TraceBackService;
import com.axelor.i18n.I18n;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.persistence.EntityTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IrrecoverableService {

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected SequenceService sequenceService;
  protected MoveToolService moveToolService;
  protected MoveCreateService moveCreateService;
  protected MoveValidateService moveValidateService;
  protected MoveLineCreateService moveLineCreateService;
  protected MoveLineRepository moveLineRepo;
  protected ReconcileService reconcileService;
  protected TaxService taxService;
  protected TaxAccountService taxAccountService;
  protected PaymentScheduleService paymentScheduleService;
  protected PaymentScheduleRepository paymentScheduleRepo;
  protected PaymentScheduleLineRepository paymentScheduleLineRepo;
  protected AccountConfigService accountConfigService;
  protected IrrecoverableCustomerLineRepository irrecoverableCustomerLineRepo;
  protected InvoiceRepository invoiceRepo;
  protected ManagementObjectRepository managementObjectRepo;
  protected IrrecoverableRepository irrecoverableRepo;

  protected AppAccountService appAccountService;

  @Inject
  public IrrecoverableService(
      AppAccountService appAccountService,
      SequenceService sequenceService,
      MoveToolService moveToolService,
      MoveCreateService moveCreateService,
      MoveValidateService moveValidateService,
      MoveLineCreateService moveLineCreateService,
      MoveLineRepository moveLineRepo,
      ReconcileService reconcileService,
      TaxService taxService,
      TaxAccountService taxAccountService,
      PaymentScheduleService paymentScheduleService,
      PaymentScheduleRepository paymentScheduleRepo,
      PaymentScheduleLineRepository paymentScheduleLineRepo,
      AccountConfigService accountConfigService,
      IrrecoverableCustomerLineRepository irrecoverableCustomerLineRepo,
      InvoiceRepository invoiceRepo,
      ManagementObjectRepository managementObjectRepo,
      IrrecoverableRepository irrecoverableRepo) {

    this.sequenceService = sequenceService;
    this.moveToolService = moveToolService;
    this.moveCreateService = moveCreateService;
    this.moveValidateService = moveValidateService;
    this.moveLineCreateService = moveLineCreateService;
    this.moveLineRepo = moveLineRepo;
    this.reconcileService = reconcileService;
    this.taxService = taxService;
    this.taxAccountService = taxAccountService;
    this.paymentScheduleService = paymentScheduleService;
    this.paymentScheduleRepo = paymentScheduleRepo;
    this.paymentScheduleLineRepo = paymentScheduleLineRepo;
    this.accountConfigService = accountConfigService;
    this.irrecoverableCustomerLineRepo = irrecoverableCustomerLineRepo;
    this.invoiceRepo = invoiceRepo;
    this.managementObjectRepo = managementObjectRepo;
    this.irrecoverableRepo = irrecoverableRepo;

    this.appAccountService = appAccountService;
  }

  /**
   * Proc??dure permettant de remplir la liste des factures et ??ch??ances rejet??es ?? passer en
   * irr??couvrable d'une soci??t??, ansi que de remplir le champ nom de l'objet irr??couvrable
   *
   * @param irrecoverable Un objet Irr??couvrable
   * @throws AxelorException
   */
  @Transactional(rollbackOn = {Exception.class})
  public void getIrrecoverable(Irrecoverable irrecoverable) throws AxelorException {

    Company company = irrecoverable.getCompany();

    this.testCompanyField(company);

    if (irrecoverable.getName() == null) {
      irrecoverable.setName(this.getSequence(company));
    }

    irrecoverable.setInvoiceSet(new HashSet<Invoice>());
    irrecoverable.getInvoiceSet().addAll(this.getInvoiceList(company));
    irrecoverable.getInvoiceSet().addAll(this.getRejectInvoiceList(company));

    irrecoverable.setPaymentScheduleLineSet(new HashSet<PaymentScheduleLine>());
    irrecoverable.getPaymentScheduleLineSet().addAll(this.getPaymentScheduleLineList(company));

    irrecoverableRepo.save(irrecoverable);
  }

  /**
   * Fonction permettant de r??cup??rer la liste des tiers payeur associ??s ?? une liste de factures
   *
   * @param invoiceList Une liste de factures
   * @return
   */
  public List<Partner> getPayerPartnerList(Set<Invoice> invoiceList) {
    List<Partner> partnerList = new ArrayList<Partner>();

    for (Invoice invoice : invoiceList) {
      if (!partnerList.contains(invoice.getPartner())) {
        partnerList.add(invoice.getPartner());
      }
    }
    return partnerList;
  }

  /**
   * Fonction permettant de r??cup??rer la liste des factures ?? passer en irr??couvrable d'une soci??t??
   *
   * @param company Une soci??t??
   * @return
   */
  public List<Invoice> getInvoiceList(Company company) {
    return invoiceRepo
        .all()
        .filter(
            "self.irrecoverableStatusSelect = ?1 AND self.company = ?2 AND self.statusSelect = ?3 "
                + "AND self.companyInTaxTotalRemaining > 0 AND self.rejectMoveLine IS NULL",
            InvoiceRepository.IRRECOVERABLE_STATUS_TO_PASS_IN_IRRECOUVRABLE,
            company,
            InvoiceRepository.STATUS_VENTILATED)
        .order("dueDate")
        .fetch();
  }

  /**
   * Fonction permettant de r??cup??rer la liste des factures rejet??es ?? passer en irr??couvrable d'une
   * soci??t??
   *
   * @param company Une soci??t??
   * @return
   */
  public List<Invoice> getRejectInvoiceList(Company company) {
    return invoiceRepo
        .all()
        .filter(
            "self.irrecoverableStatusSelect = ?1 AND self.company = ?2 AND self.statusSelect = ?3 "
                + "AND self.companyInTaxTotalRemaining = 0 AND self.rejectMoveLine IS NOT NULL",
            InvoiceRepository.IRRECOVERABLE_STATUS_TO_PASS_IN_IRRECOUVRABLE,
            company,
            InvoiceRepository.STATUS_VENTILATED)
        .order("dueDate")
        .fetch();
  }

  /**
   * Fonction permettant de r??cup??rer la liste des ??ch??ances rejet??es ?? passer une irr??couvrable
   * d'une soci??t??
   *
   * @param company Une soci??t??
   * @return
   */
  public List<PaymentScheduleLine> getPaymentScheduleLineList(Company company) {
    return paymentScheduleLineRepo
        .all()
        .filter(
            "self.paymentSchedule.irrecoverableStatusSelect = ?1 AND self.paymentSchedule.company = ?2 "
                + "AND self.paymentSchedule.statusSelect = ?3 AND self.rejectMoveLine.amountRemaining > 0",
            PaymentScheduleRepository.IRRECOVERABLE_STATUS_TO_PASS_IN_IRRECOUVRABLE,
            company,
            PaymentScheduleRepository.STATUS_CONFIRMED)
        .order("scheduleDate")
        .fetch();
  }

  /**
   * Fonction permettant de r??cup??rer les factures ?? passer en irr??couvrable d'un tiers
   *
   * @param partner Un tiers
   * @param allInvoiceList La liste des factures ?? passer en irr??couvrable de la soci??t??
   * @return
   */
  public List<Invoice> getInvoiceList(Partner partner, Set<Invoice> allInvoiceList) {
    List<Invoice> invoiceList = new ArrayList<Invoice>();

    for (Invoice invoice : allInvoiceList) {
      if (invoice.getPartner().equals(partner)) {
        invoiceList.add(invoice);
      }
    }

    log.debug("Nombre de facture ?? passer en irr??couvrable pour le tiers : {}", invoiceList.size());

    return invoiceList;
  }

  /**
   * Fonction permettant de r??cup??rer les ??ch??ances rejet??es ?? passer en irr??couvrable d'un tiers
   *
   * @param payerPartner Un tiers payeur
   * @param allPaymentScheduleLineList La liste des ??ch??ances rejet??es ?? passer en irr??couvrable de
   *     la soci??t??
   * @return
   */
  public List<PaymentScheduleLine> getPaymentScheduleLineList(
      Partner payerPartner, Set<PaymentScheduleLine> allPaymentScheduleLineList) {
    List<PaymentScheduleLine> paymentScheduleLineList = new ArrayList<PaymentScheduleLine>();

    for (PaymentScheduleLine paymentScheduleLine : allPaymentScheduleLineList) {
      if (paymentScheduleLine.getPaymentSchedule().getPartner().equals(payerPartner)) {
        paymentScheduleLineList.add(paymentScheduleLine);
      }
    }

    log.debug(
        "Nombre d'??ch??ances ?? passer en irr??couvrable pour le tiers : {}",
        paymentScheduleLineList.size());

    return paymentScheduleLineList;
  }

  /**
   * Proc??dure permettant de passer en irr??couvrables les factures et ??ch??ances rejet??es r??cup??r??
   * sur l'objet Irr??couvrable
   *
   * @param irrecoverable Un objet Irr??couvrable
   */
  @Transactional
  public void createIrrecoverableReport(Irrecoverable irrecoverable) {

    Set<Invoice> invoiceSet = irrecoverable.getInvoiceSet();
    Set<PaymentScheduleLine> paymentScheduleLineSet = irrecoverable.getPaymentScheduleLineSet();

    irrecoverable.setMoveSet(new HashSet<Move>());

    List<Partner> payerPartnerList = this.getPayerPartnerList(invoiceSet);

    EntityTransaction transaction = JPA.em().getTransaction();

    int i = 0;
    if (payerPartnerList != null && payerPartnerList.size() != 0) {
      for (Partner payerPartner : payerPartnerList) {

        if (!transaction.isActive()) {
          transaction.begin();
        }

        i++;
        try {
          log.debug("Tiers : {}", payerPartner.getName());
          this.createIrrecoverableCustomerLine(
              irrecoverable,
              payerPartner,
              this.getInvoiceList(payerPartner, invoiceSet),
              this.getPaymentScheduleLineList(payerPartner, paymentScheduleLineSet));
          irrecoverableRepo.save(irrecoverable);
          transaction.commit();

          if (i % 50 == 0) {
            JPA.flush();
            JPA.clear();
          }

        } catch (Exception e) {
          TraceBackService.trace(e);
          log.error("Bug(Anomalie) g??n??r??(e) pour le tiers : {}", payerPartner.getName());

        } finally {
          if (!transaction.isActive()) {
            transaction.begin();
          }
        }
      }
    }
  }

  /**
   * Fonction permettant de cr??er une ligne Client
   *
   * @param irrecoverable Un objet Irr??couvrable
   * @param payerPartner Un tiers payeur
   * @param invoiceList Une liste de facture du tiers payeur
   * @param paymentScheduleLineList Une liste d'??ch??ancier du tiers payeur
   * @return
   * @throws AxelorException
   */
  @Transactional(rollbackOn = {Exception.class})
  public IrrecoverableCustomerLine createIrrecoverableCustomerLine(
      Irrecoverable irrecoverable,
      Partner payerPartner,
      List<Invoice> invoiceList,
      List<PaymentScheduleLine> paymentScheduleLineList)
      throws AxelorException {
    IrrecoverableCustomerLine icl = new IrrecoverableCustomerLine();
    icl.setIrrecoverable(irrecoverable);
    irrecoverableCustomerLineRepo.save(icl);
    irrecoverable.getIrrecoverableCustomerLineList().add(icl);
    icl.setPartner(payerPartner);
    icl.setIrrecoverablePaymentScheduleLineLineList(
        this.createIrrecoverablePaymentScheduleLineLineList(icl, paymentScheduleLineList));
    icl.setIrrecoverableInvoiceLineList(this.createIrrecoverableInvoiceLineList(icl, invoiceList));

    log.debug("Ligne client : {}", icl);

    return icl;
  }

  public Irrecoverable retrieveAndInit(Irrecoverable irrecoverable) {
    irrecoverable = irrecoverableRepo.find(irrecoverable.getId());
    if (irrecoverable.getMoveSet() == null) {
      irrecoverable.setMoveSet(Sets.newHashSet());
    }
    return irrecoverable;
  }

  @Transactional(rollbackOn = {Exception.class})
  public void manageIrrecoverableInvoice(Irrecoverable irrecoverable, Invoice invoice)
      throws AxelorException {

    log.debug("Facture : {}", invoice.getInvoiceId());
    irrecoverable = retrieveAndInit(irrecoverable);
    invoice = invoiceRepo.find(invoice.getId());
    this.createIrrecoverableInvoiceLineMove(irrecoverable, invoice);

    irrecoverableRepo.save(irrecoverable);
  }

  @Transactional(rollbackOn = {Exception.class})
  public void manageIrrecoverablePaymentScheduleLine(
      Irrecoverable irrecoverable, PaymentScheduleLine paymentScheduleLine) throws AxelorException {

    log.debug("Ligne d'??ch??ancier : {}", paymentScheduleLine.getName());
    irrecoverable = retrieveAndInit(irrecoverable);
    paymentScheduleLine = paymentScheduleLineRepo.find(paymentScheduleLine.getId());
    this.createMoveForPaymentScheduleLineReject(irrecoverable, paymentScheduleLine);

    irrecoverableRepo.save(irrecoverable);
  }

  /**
   * Proc??dure permettant de
   *
   * @param irrecoverable
   * @throws AxelorException
   */
  public int passInIrrecoverable(Irrecoverable irrecoverable) throws AxelorException {

    int anomaly = 0;

    this.testCompanyField(irrecoverable.getCompany());

    if (irrecoverable.getInvoiceSet() != null && !irrecoverable.getInvoiceSet().isEmpty()) {
      for (Invoice invoice : irrecoverable.getInvoiceSet()) {
        try {
          this.manageIrrecoverableInvoice(irrecoverable, invoice);
        } catch (AxelorException e) {
          anomaly++;
          TraceBackService.trace(
              new AxelorException(
                  e, e.getCategory(), I18n.get("Invoice") + " %s", invoice.getInvoiceId()),
              ExceptionOriginRepository.IRRECOVERABLE,
              irrecoverable.getId());
          log.error("Bug(Anomalie) g??n??r??(e) pour la facture : {}", invoice.getInvoiceId());

        } catch (Exception e) {
          anomaly++;
          TraceBackService.trace(
              new Exception(String.format(I18n.get("Invoice") + " %s", invoice.getInvoiceId()), e),
              ExceptionOriginRepository.IRRECOVERABLE,
              irrecoverable.getId());
          log.error("Bug(Anomalie) g??n??r??(e) pour la facture : {}", invoice.getInvoiceId());
        }
      }
    }
    irrecoverable = this.retrieveAndInit(irrecoverable);
    if (irrecoverable.getPaymentScheduleLineSet() != null
        && irrecoverable.getPaymentScheduleLineSet().size() != 0) {
      for (PaymentScheduleLine paymentScheduleLine : irrecoverable.getPaymentScheduleLineSet()) {

        try {
          this.manageIrrecoverablePaymentScheduleLine(irrecoverable, paymentScheduleLine);

        } catch (AxelorException e) {
          anomaly++;
          TraceBackService.trace(
              new AxelorException(
                  e,
                  e.getCategory(),
                  I18n.get(IExceptionMessage.IRRECOVERABLE_1),
                  paymentScheduleLine.getName()),
              ExceptionOriginRepository.IRRECOVERABLE,
              irrecoverable.getId());
          log.error(
              "Bug(Anomalie) g??n??r??(e) pour la ligne d'??ch??ancier : {}",
              paymentScheduleLine.getName());

        } catch (Exception e) {
          anomaly++;
          TraceBackService.trace(
              new Exception(
                  String.format(
                      I18n.get(IExceptionMessage.IRRECOVERABLE_1), paymentScheduleLine.getName()),
                  e),
              ExceptionOriginRepository.IRRECOVERABLE,
              irrecoverable.getId());
          log.error(
              "Bug(Anomalie) g??n??r??(e) pour la ligne d'??ch??ancier : {}",
              paymentScheduleLine.getName());
        }
      }
    }
    irrecoverable = this.retrieveAndInit(irrecoverable);
    if (irrecoverable != null
        && irrecoverable.getMoveSet() != null
        && !irrecoverable.getMoveSet().isEmpty()) {
      EntityTransaction transaction = JPA.em().getTransaction();
      if (!transaction.isActive()) {
        transaction.begin();
      }

      irrecoverable.setStatusSelect(IrrecoverableRepository.STATUS_VALIDATED);
      irrecoverableRepo.save(irrecoverable);
      transaction.commit();
    }

    return anomaly;
  }

  @Transactional(rollbackOn = {Exception.class})
  public void createMoveForPaymentScheduleLineReject(
      Irrecoverable irrecoverable, PaymentScheduleLine paymentScheduleLine) throws AxelorException {

    Move move =
        this.createIrrecoverableMove(
            paymentScheduleLine.getRejectMoveLine(), irrecoverable.getName());
    if (move == null) {
      throw new AxelorException(
          irrecoverable,
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.IRRECOVERABLE_2),
          I18n.get(com.axelor.apps.base.exceptions.IExceptionMessage.EXCEPTION));
    }
    moveValidateService.validate(move);
    irrecoverable.getMoveSet().add(move);
  }

  @Transactional(rollbackOn = {Exception.class})
  public void createIrrecoverableInvoiceLineMove(Irrecoverable irrecoverable, Invoice invoice)
      throws AxelorException {

    BigDecimal prorataRate = this.getProrataRate(invoice, invoice.getRejectMoveLine() != null);

    // Ajout de l'??criture g??n??r??e
    Move move =
        this.createIrrecoverableMove(
            invoice, prorataRate, invoice.getRejectMoveLine() != null, irrecoverable.getName());
    if (move == null) {
      throw new AxelorException(
          irrecoverable,
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.IRRECOVERABLE_2),
          I18n.get(com.axelor.apps.base.exceptions.IExceptionMessage.EXCEPTION));
    }
    moveValidateService.validate(move);
    irrecoverable.getMoveSet().add(move);

    invoice.setIrrecoverableStatusSelect(
        InvoiceRepository.IRRECOVERABLE_STATUS_PASSED_IN_IRRECOUVRABLE);

    if (invoice.getCanceledPaymentSchedule() != null
        && this.isAllInvoicePassedInIrrecoverable(invoice.getCanceledPaymentSchedule())) {
      invoice
          .getCanceledPaymentSchedule()
          .setIrrecoverableStatusSelect(
              PaymentScheduleRepository.IRRECOVERABLE_STATUS_PASSED_IN_IRRECOUVRABLE);
    }
  }

  /**
   * Fonction permettant de cr??er une liste de ligne Facture
   *
   * @param icl Une ligne Client
   * @param invoiceList Une liste de factures du tiers payeur
   * @return
   * @throws AxelorException
   */
  public List<IrrecoverableInvoiceLine> createIrrecoverableInvoiceLineList(
      IrrecoverableCustomerLine icl, List<Invoice> invoiceList) throws AxelorException {
    int seq = 1;
    List<IrrecoverableInvoiceLine> iilList = new ArrayList<IrrecoverableInvoiceLine>();
    for (Invoice invoice : invoiceList) {
      iilList.add(this.createIrrecoverableInvoiceLine(icl, invoice, seq));
      seq++;
    }
    return iilList;
  }

  /**
   * Fonction permettant de cr??er une liste de ligne d'??ch??ance rejet??e
   *
   * @param icl Une ligne Client
   * @param invoiceList Une liste de d'??ch??ance rejet??e du tiers payeur
   * @return
   * @throws AxelorException
   */
  public List<IrrecoverablePaymentScheduleLineLine> createIrrecoverablePaymentScheduleLineLineList(
      IrrecoverableCustomerLine icl, List<PaymentScheduleLine> paymentScheduleLineList)
      throws AxelorException {
    int seq = 1;
    List<IrrecoverablePaymentScheduleLineLine> ipsllList =
        new ArrayList<IrrecoverablePaymentScheduleLineLine>();
    for (PaymentScheduleLine paymentScheduleLine : paymentScheduleLineList) {
      ipsllList.add(this.createIrrecoverablePaymentScheduleLineLine(icl, paymentScheduleLine, seq));
      seq++;
    }
    return ipsllList;
  }

  /**
   * Fonction permettant de cr??er une ligne Facture
   *
   * @param icl Une ligne Client
   * @param invoice Une facture
   * @param seq Un num??ro de s??quence
   * @return
   * @throws AxelorException
   */
  public IrrecoverableInvoiceLine createIrrecoverableInvoiceLine(
      IrrecoverableCustomerLine icl, Invoice invoice, int seq) throws AxelorException {
    IrrecoverableInvoiceLine iil = new IrrecoverableInvoiceLine();
    iil.setInvoice(invoice);
    iil.setInvoiceLineSeq(seq);
    iil.setIrrecoverableCustomerLine(icl);

    BigDecimal prorataRate = this.getProrataRate(invoice, invoice.getRejectMoveLine() != null);

    iil.setIrrecoverableReportLineList(
        this.createIrrecoverableReportLineList(iil, invoice, prorataRate));

    log.debug("Ligne facture : {}", iil);

    return iil;
  }

  /**
   * Fonction permettant de cr??er une ligne Ech??ance rejet??e
   *
   * @param icl Une ligne Client
   * @param invoice Une ??ch??ance rejet??e
   * @param seq Un num??ro de s??quence
   * @return
   * @throws AxelorException
   */
  public IrrecoverablePaymentScheduleLineLine createIrrecoverablePaymentScheduleLineLine(
      IrrecoverableCustomerLine icl, PaymentScheduleLine paymentScheduleLine, int seq)
      throws AxelorException {
    IrrecoverablePaymentScheduleLineLine ipsll = new IrrecoverablePaymentScheduleLineLine();
    ipsll.setPaymentScheduleLine(paymentScheduleLine);
    ipsll.setIrrecoverableCustomerLine(icl);

    Company company = paymentScheduleLine.getPaymentSchedule().getCompany();

    Tax tax =
        accountConfigService.getIrrecoverableStandardRateTax(
            accountConfigService.getAccountConfig(company));

    ipsll.setIrrecoverableReportLineList(
        this.createIrrecoverableReportLineList(ipsll, paymentScheduleLine, tax));

    log.debug("Ligne ??ch??ance rejet??e : {}", ipsll);

    return ipsll;
  }

  /**
   * Fonction permettant de savoir si toutes les factures ?? passer en irr??couvrable d'un ??ch??ancier
   * ?? passer en irr??couvrable, ont ??t?? pass??es en irr??couvrable
   *
   * @param paymentSchedule Un ??ch??ancier
   * @return
   */
  public boolean isAllInvoicePassedInIrrecoverable(PaymentSchedule paymentSchedule) {
    for (Invoice invoiceScheduled : paymentSchedule.getInvoiceSet()) {
      if (invoiceScheduled
          .getIrrecoverableStatusSelect()
          .equals(InvoiceRepository.IRRECOVERABLE_STATUS_TO_PASS_IN_IRRECOUVRABLE)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Fonction permettant de cr??er une liste de ligne de reporting pour une ligne Facture
   *
   * @param iil Une ligne Facture
   * @param invoice Une facture
   * @param prorataRate Un taux de restant ?? payer d'une facture
   * @return
   */
  public List<IrrecoverableReportLine> createIrrecoverableReportLineList(
      IrrecoverableInvoiceLine iil, Invoice invoice, BigDecimal prorataRate) {
    int seq = 1;
    List<IrrecoverableReportLine> irlList = new ArrayList<IrrecoverableReportLine>();

    for (InvoiceLine invoiceLine : invoice.getInvoiceLineList()) {

      irlList.add(
          this.createIrrecoverableReportLine(
              iil,
              invoiceLine.getName(),
              invoiceLine
                  .getExTaxTotal()
                  .multiply(prorataRate)
                  .setScale(AppBaseService.DEFAULT_NB_DECIMAL_DIGITS, RoundingMode.HALF_UP),
              seq));
      seq++;
    }
    for (InvoiceLineTax invoiceLineTax : invoice.getInvoiceLineTaxList()) {
      irlList.add(
          this.createIrrecoverableReportLine(
              iil,
              invoiceLineTax.getTaxLine().getTax().getName(),
              invoiceLineTax
                  .getTaxTotal()
                  .multiply(prorataRate)
                  .setScale(AppBaseService.DEFAULT_NB_DECIMAL_DIGITS, RoundingMode.HALF_UP),
              seq));
      seq++;
    }
    // Afin de ne pas modifier les valeurs des lignes de factures, on les recharges depuis la base
    invoiceRepo.refresh(invoice);
    return irlList;
  }

  /**
   * Fonction permettant de cr??er une liste de ligne de reporting pour une ligne Ech??ance rejet??e
   *
   * @param iil Une ligne Ech??ance rejet??e
   * @param invoice Une ??ch??ance rejet??e
   * @param prorataRate Un taux de restant ?? payer d'une ??ch??ance rejet??e
   * @return
   * @throws AxelorException
   */
  public List<IrrecoverableReportLine> createIrrecoverableReportLineList(
      IrrecoverablePaymentScheduleLineLine ipsll, PaymentScheduleLine paymentScheduleLine, Tax tax)
      throws AxelorException {
    List<IrrecoverableReportLine> irlList = new ArrayList<IrrecoverableReportLine>();

    BigDecimal taxRate =
        taxService.getTaxRate(
            tax,
            appAccountService.getTodayDate(
                paymentScheduleLine.getPaymentSchedule() != null
                    ? paymentScheduleLine.getPaymentSchedule().getCompany()
                    : Optional.ofNullable(AuthUtils.getUser())
                        .map(User::getActiveCompany)
                        .orElse(null)));

    BigDecimal amount = paymentScheduleLine.getInTaxAmount();

    BigDecimal divid = taxRate.add(BigDecimal.ONE);

    // Montant hors-Taxe
    BigDecimal irrecoverableAmount =
        amount
            .divide(divid, 6, RoundingMode.HALF_UP)
            .setScale(AppBaseService.DEFAULT_NB_DECIMAL_DIGITS, RoundingMode.HALF_UP);

    // Montant Tax
    BigDecimal taxAmount = amount.subtract(irrecoverableAmount);

    irlList.add(this.createIrrecoverableReportLine(ipsll, "HT", irrecoverableAmount, 1));

    irlList.add(this.createIrrecoverableReportLine(ipsll, tax.getName(), taxAmount, 2));

    return irlList;
  }

  /**
   * Fonction permettant de cr??er une ligne Reporting
   *
   * @param iil Une ligne Facture
   * @param label Un libell??
   * @param value Une valeur
   * @param seq Un num??ro de s??quence
   * @return
   */
  public IrrecoverableReportLine createIrrecoverableReportLine(
      IrrecoverableInvoiceLine iil, String label, BigDecimal value, int seq) {
    IrrecoverableReportLine irl = new IrrecoverableReportLine();
    irl.setReportLineSeq(seq);
    irl.setLabel(label);
    irl.setValue(value);
    irl.setIrrecoverableInvoiceLine(iil);

    log.debug("Ligne reporting : {}", irl);

    return irl;
  }

  /**
   * Fonction permettant de cr??er une ligne Reporting
   *
   * @param iil Une ligne Ech??ance rejet??e
   * @param label Un libell??
   * @param value Une valeur
   * @param seq Un num??ro de s??quence
   * @return
   */
  public IrrecoverableReportLine createIrrecoverableReportLine(
      IrrecoverablePaymentScheduleLineLine ipsll, String label, BigDecimal value, int seq) {
    IrrecoverableReportLine irl = new IrrecoverableReportLine();
    irl.setReportLineSeq(seq);
    irl.setLabel(label);
    irl.setValue(value);
    irl.setIrrecoverablePaymentScheduleLineLine(ipsll);

    log.debug("Ligne reporting : {}", irl);

    return irl;
  }

  /**
   * Fonction permettant de calculer le taux de restant ?? payer d'une facture
   *
   * @param invoice Une facture
   * @param isInvoiceReject La facture est-elle rejet??e?
   * @return
   */
  public BigDecimal getProrataRate(Invoice invoice, boolean isInvoiceReject) {
    BigDecimal prorataRate = null;
    if (isInvoiceReject) {
      prorataRate =
          (invoice.getRejectMoveLine().getAmountRemaining())
              .divide(invoice.getInTaxTotal(), 6, RoundingMode.HALF_UP);
    } else {
      prorataRate =
          invoice
              .getCompanyInTaxTotalRemaining()
              .divide(invoice.getInTaxTotal(), 6, RoundingMode.HALF_UP);
    }

    log.debug("Taux d'impay?? pour la facture {} : {}", invoice.getInvoiceId(), prorataRate);

    return prorataRate;
  }

  /**
   * Fonction permettant de cr??er l'??criture de passage en irr??couvrable d'une facture
   *
   * @param invoice Une facture
   * @param prorataRate Le taux de restant ?? payer sur la facture
   * @param isInvoiceReject La facture est-elle rejet??e?
   * @return
   * @throws AxelorException
   */
  public Move createIrrecoverableMove(
      Invoice invoice, BigDecimal prorataRate, boolean isInvoiceReject, String irrecoverableName)
      throws AxelorException {
    Company company = invoice.getCompany();
    Partner payerPartner = invoice.getPartner();

    AccountConfig accountConfig = company.getAccountConfig();
    // Move
    Move move =
        moveCreateService.createMove(
            accountConfig.getIrrecoverableJournal(),
            company,
            null,
            payerPartner,
            null,
            invoice.getFiscalPosition(),
            MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC,
            MoveRepository.FUNCTIONAL_ORIGIN_SALE,
            irrecoverableName,
            invoice.getInvoiceId());

    int seq = 1;

    BigDecimal amount = BigDecimal.ZERO;
    MoveLine debitMoveLine = null;
    BigDecimal creditAmount = null;
    BigDecimal debitAmount = null;
    if (isInvoiceReject) {
      creditAmount = invoice.getRejectMoveLine().getAmountRemaining();
      debitAmount = creditAmount;
    } else {
      creditAmount = invoice.getCompanyInTaxTotalRemaining();
      debitAmount = creditAmount;
    }

    // Debits MoveLines Tva
    for (InvoiceLineTax invoiceLineTax : invoice.getInvoiceLineTaxList()) {
      amount =
          (invoiceLineTax.getTaxTotal().multiply(prorataRate)).setScale(2, RoundingMode.HALF_UP);
      // do not generate move line with amount equal to zero
      if (amount.signum() == 0) {
        continue;
      }
      debitMoveLine =
          moveLineCreateService.createMoveLine(
              move,
              payerPartner,
              taxAccountService.getAccount(
                  invoiceLineTax.getTaxLine().getTax(), company, false, false),
              amount,
              true,
              appAccountService.getTodayDate(company),
              seq,
              irrecoverableName,
              invoice.getInvoiceId());

      move.getMoveLineList().add(debitMoveLine);
      seq++;
      debitAmount = debitAmount.subtract(amount);
    }

    // Debit MoveLine 654 (irrecoverable account)
    debitMoveLine =
        moveLineCreateService.createMoveLine(
            move,
            payerPartner,
            accountConfig.getIrrecoverableAccount(),
            debitAmount,
            true,
            appAccountService.getTodayDate(company),
            seq,
            irrecoverableName,
            invoice.getInvoiceId());
    move.getMoveLineList().add(debitMoveLine);

    seq++;

    // Getting customer MoveLine from Facture
    MoveLine customerMoveLine = moveToolService.getCustomerMoveLineByQuery(invoice);
    if (customerMoveLine == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.IRRECOVERABLE_3),
          I18n.get(com.axelor.apps.base.exceptions.IExceptionMessage.EXCEPTION),
          invoice.getInvoiceId());
    }
    customerMoveLine.setIrrecoverableStatusSelect(
        MoveLineRepository.IRRECOVERABLE_STATUS_PASSED_IN_IRRECOUVRABLE);

    // Credit MoveLine Customer account (411, 416, ...)
    MoveLine creditMoveLine =
        moveLineCreateService.createMoveLine(
            move,
            payerPartner,
            customerMoveLine.getAccount(),
            creditAmount,
            false,
            appAccountService.getTodayDate(company),
            seq,
            irrecoverableName,
            invoice.getInvoiceId());
    move.getMoveLineList().add(creditMoveLine);

    Reconcile reconcile =
        reconcileService.createReconcile(customerMoveLine, creditMoveLine, creditAmount, false);
    if (reconcile != null) {
      reconcileService.confirmReconcile(reconcile, true);
    }

    return move;
  }

  /**
   * Fonction permettant de cr??er l'??criture de passage en irr??couvrable d'une ??ch??ance
   *
   * @param moveLine Une ??criture d'??ch??ance
   * @return
   * @throws AxelorException
   */
  public Move createIrrecoverableMove(MoveLine moveLine, String irrecoverableName)
      throws AxelorException {

    Company company = moveLine.getMove().getCompany();
    Partner payerPartner = moveLine.getPartner();
    BigDecimal amount = moveLine.getAmountRemaining();

    AccountConfig accountConfig = company.getAccountConfig();

    // Move
    Move move =
        moveCreateService.createMove(
            accountConfig.getIrrecoverableJournal(),
            company,
            null,
            payerPartner,
            null,
            payerPartner != null ? payerPartner.getFiscalPosition() : null,
            MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC,
            moveLine.getMove().getFunctionalOriginSelect(),
            irrecoverableName,
            moveLine.getDescription());

    int seq = 1;

    // Credit MoveLine Customer account (411, 416, ...)
    MoveLine creditMoveLine =
        moveLineCreateService.createMoveLine(
            move,
            payerPartner,
            moveLine.getAccount(),
            amount,
            false,
            appAccountService.getTodayDate(company),
            seq,
            irrecoverableName,
            moveLine.getDescription());
    move.getMoveLineList().add(creditMoveLine);

    Reconcile reconcile = reconcileService.createReconcile(moveLine, creditMoveLine, amount, false);
    if (reconcile != null) {
      reconcileService.confirmReconcile(reconcile, true);
    }

    Tax tax = accountConfig.getIrrecoverableStandardRateTax();

    BigDecimal taxRate = taxService.getTaxRate(tax, appAccountService.getTodayDate(company));

    // Debit MoveLine 654. (irrecoverable account)
    BigDecimal divid = taxRate.add(BigDecimal.ONE);
    BigDecimal irrecoverableAmount =
        amount
            .divide(divid, 6, RoundingMode.HALF_UP)
            .setScale(AppBaseService.DEFAULT_NB_DECIMAL_DIGITS, RoundingMode.HALF_UP);
    MoveLine creditMoveLine1 =
        moveLineCreateService.createMoveLine(
            move,
            payerPartner,
            accountConfig.getIrrecoverableAccount(),
            irrecoverableAmount,
            true,
            appAccountService.getTodayDate(company),
            2,
            irrecoverableName,
            moveLine.getDescription());
    move.getMoveLineList().add(creditMoveLine1);

    // Debit MoveLine 445 (Tax account)
    Account taxAccount = taxAccountService.getAccount(tax, company, false, false);
    BigDecimal taxAmount = amount.subtract(irrecoverableAmount);
    MoveLine creditMoveLine2 =
        moveLineCreateService.createMoveLine(
            move,
            payerPartner,
            taxAccount,
            taxAmount,
            true,
            appAccountService.getTodayDate(company),
            3,
            irrecoverableName,
            moveLine.getDescription());
    move.getMoveLineList().add(creditMoveLine2);

    return move;
  }

  /**
   * Fonction permettant de cr??er un objet de gestion
   *
   * @param code
   * @param message
   * @return
   */
  public ManagementObject createManagementObject(String code, String message) {
    ManagementObject managementObject =
        managementObjectRepo
            .all()
            .filter("self.code = ?1 AND self.name = ?2", code, message)
            .fetchOne();
    if (managementObject != null) {
      return managementObject;
    }

    managementObject = new ManagementObject();
    managementObject.setCode(code);
    managementObject.setName(message);
    return managementObject;
  }

  /**
   * Proc??dure permettant de v??rifier les champs d'une soci??t??
   *
   * @param company Une soci??t??
   * @throws AxelorException
   */
  public void testCompanyField(Company company) throws AxelorException {

    AccountConfig accountConfig = accountConfigService.getAccountConfig(company);
    accountConfigService.getIrrecoverableAccount(accountConfig);
    accountConfigService.getIrrecoverableJournal(accountConfig);
    accountConfigService.getIrrecoverableStandardRateTax(accountConfig);
  }

  public String getSequence(Company company) throws AxelorException {

    String seq = sequenceService.getSequenceNumber(SequenceRepository.IRRECOVERABLE, company);
    if (seq == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(IExceptionMessage.IRRECOVERABLE_4),
          I18n.get(com.axelor.apps.base.exceptions.IExceptionMessage.EXCEPTION),
          company.getName());
    }

    return seq;
  }

  /**
   * Proc??dure permettant de passer une facture en irr??couvrable
   *
   * @param invoice Une facture
   * @param generateEvent Un ??v??nement ?? t'il d??j?? ??t?? cr???? par un autre objet ?
   * @throws AxelorException
   */
  @Transactional(rollbackOn = {Exception.class})
  public void passInIrrecoverable(Invoice invoice, boolean generateEvent) throws AxelorException {
    invoice.setIrrecoverableStatusSelect(
        InvoiceRepository.IRRECOVERABLE_STATUS_TO_PASS_IN_IRRECOUVRABLE);

    if (generateEvent) {
      Company company = invoice.getCompany();

      ManagementObject managementObject =
          this.createManagementObject(
              "IRR",
              accountConfigService.getIrrecoverableReasonPassage(
                  accountConfigService.getAccountConfig(company)));
      invoice.setManagementObject(managementObject);

      MoveLine moveLine = moveToolService.getCustomerMoveLineByQuery(invoice);

      if (moveLine == null) {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_INCONSISTENCY,
            I18n.get(IExceptionMessage.IRRECOVERABLE_3),
            I18n.get(com.axelor.apps.base.exceptions.IExceptionMessage.EXCEPTION),
            invoice.getInvoiceId());
      }

      this.passInIrrecoverable(moveLine, managementObject, false);
    }

    invoiceRepo.save(invoice);
  }

  /**
   * Proc??dure permettant de passer une facture en irr??couvrable
   *
   * @param invoice Une facture
   * @param managementObject Un objet de gestion (utilis?? si proc??dure appel??e depuis un autre
   *     objet)
   * @throws AxelorException
   */
  public void passInIrrecoverable(Invoice invoice, ManagementObject managementObject)
      throws AxelorException {
    this.passInIrrecoverable(invoice, false);
    invoice.setManagementObject(managementObject);
    MoveLine moveLine = moveToolService.getCustomerMoveLineByQuery(invoice);

    if (moveLine == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.IRRECOVERABLE_3),
          I18n.get(com.axelor.apps.base.exceptions.IExceptionMessage.EXCEPTION),
          invoice.getInvoiceId());
    }

    this.passInIrrecoverable(moveLine, managementObject, false);

    invoiceRepo.save(invoice);
  }

  /**
   * Proc??dure permettant d'annuler le passage en irr??couvrable d'une facture
   *
   * @param invoice Une facture
   * @throws AxelorException
   */
  @Transactional(rollbackOn = {Exception.class})
  public void notPassInIrrecoverable(Invoice invoice) throws AxelorException {
    invoice.setIrrecoverableStatusSelect(InvoiceRepository.IRRECOVERABLE_STATUS_NOT_IRRECOUVRABLE);

    MoveLine moveLine = moveToolService.getCustomerMoveLineByQuery(invoice);

    if (moveLine != null) {
      this.notPassInIrrecoverable(moveLine, false);
    }

    invoiceRepo.save(invoice);
  }

  /**
   * Proc??dure permettant de passer en irr??couvrable une ligne d'??criture
   *
   * @param moveLine Une ligne d'??criture
   * @param generateEvent Un ??v??nement ?? t'il d??j?? ??t?? cr???? par un autre objet ?
   * @param passInvoice La proc??dure doit-elle passer aussi en irr??couvrable la facture ?
   * @throws AxelorException
   */
  @Transactional(rollbackOn = {Exception.class})
  public void passInIrrecoverable(MoveLine moveLine, boolean generateEvent, boolean passInvoice)
      throws AxelorException {
    moveLine.setIrrecoverableStatusSelect(
        MoveLineRepository.IRRECOVERABLE_STATUS_TO_PASS_IN_IRRECOUVRABLE);
    ManagementObject managementObject = null;
    if (generateEvent) {
      Company company = moveLine.getMove().getCompany();

      managementObject =
          this.createManagementObject(
              "IRR",
              accountConfigService.getIrrecoverableReasonPassage(
                  accountConfigService.getAccountConfig(company)));
      moveLine.setManagementObject(managementObject);
    }

    if (moveLine.getMove().getInvoice() != null && passInvoice) {
      this.passInIrrecoverable(moveLine.getMove().getInvoice(), managementObject);
    }

    moveLineRepo.save(moveLine);
  }

  /**
   * Proc??dure permettant d'annuler le passage en irrr??couvrable d'une ligne d'??criture
   *
   * @param moveLine Une ligne d'??criture
   * @param managementObject Un objet de gestion (utilis?? si proc??dure appel??e depuis un autre
   *     objet)
   * @param passInvoice La proc??dure doit-elle passer aussi en irr??couvrable la facture ?
   * @throws AxelorException
   */
  public void passInIrrecoverable(
      MoveLine moveLine, ManagementObject managementObject, boolean passInvoice)
      throws AxelorException {
    this.passInIrrecoverable(moveLine, false, passInvoice);

    moveLine.setManagementObject(managementObject);

    moveLineRepo.save(moveLine);
  }

  /**
   * Proc??dure permettant d'annuler le passage en irrr??couvrable d'une ligne d'??criture
   *
   * @param moveLine Une ligne d'??criture
   * @param passInvoice La proc??dure doit-elle passer aussi en irr??couvrable la facture ?
   * @throws AxelorException
   */
  @Transactional(rollbackOn = {Exception.class})
  public void notPassInIrrecoverable(MoveLine moveLine, boolean passInvoice)
      throws AxelorException {
    moveLine.setIrrecoverableStatusSelect(
        MoveLineRepository.IRRECOVERABLE_STATUS_NOT_IRRECOUVRABLE);

    if (moveLine.getMove().getInvoice() != null && passInvoice) {
      this.notPassInIrrecoverable(moveLine.getMove().getInvoice());
    }

    moveLineRepo.save(moveLine);
  }

  /**
   * Proc??dure permettant de passer un ??ch??ancier de lissage de paiement en irr??couvrable La
   * proc??dure passera aussi les lignes d'??criture de rejet d'??ch??ance en irr??couvrable, ainsi que
   * les factures pas compl??tement pay??e selectionn??es sur l'??ch??ancier
   *
   * @param paymentSchedule Un ??ch??ancier de paiement
   * @throws AxelorException
   */
  @Transactional(rollbackOn = {Exception.class})
  public void passInIrrecoverable(PaymentSchedule paymentSchedule) throws AxelorException {
    Company company = paymentSchedule.getCompany();

    paymentSchedule.setIrrecoverableStatusSelect(
        PaymentScheduleRepository.IRRECOVERABLE_STATUS_TO_PASS_IN_IRRECOUVRABLE);

    ManagementObject managementObject =
        this.createManagementObject(
            "IRR",
            accountConfigService.getIrrecoverableReasonPassage(
                accountConfigService.getAccountConfig(company)));
    paymentSchedule.setManagementObject(managementObject);

    List<MoveLine> paymentScheduleLineRejectMoveLineList = new ArrayList<MoveLine>();

    for (PaymentScheduleLine paymentScheduleLine : paymentSchedule.getPaymentScheduleLineList()) {
      if (paymentScheduleLine.getRejectMoveLine() != null
          && paymentScheduleLine.getRejectMoveLine().getAmountRemaining().compareTo(BigDecimal.ZERO)
              > 0) {
        paymentScheduleLineRejectMoveLineList.add(paymentScheduleLine.getRejectMoveLine());
      }
    }

    for (MoveLine moveLine : paymentScheduleLineRejectMoveLineList) {
      this.passInIrrecoverable(moveLine, managementObject, true);
    }

    for (Invoice invoice : paymentSchedule.getInvoiceSet()) {
      if (invoice.getCompanyInTaxTotalRemaining().compareTo(BigDecimal.ZERO) > 0) {
        this.passInIrrecoverable(invoice, managementObject);
      }
    }

    paymentScheduleService.cancelPaymentSchedule(paymentSchedule);

    paymentScheduleRepo.save(paymentSchedule);
  }

  /**
   * Proc??dure permettant d'annuler le passage en irr??couvrable d'une ??ch??ancier de lissage de
   * paiement La proc??dure annulera aussi le passage en irr??couvrable des lignes d'??criture de rejet
   * d'??ch??ance en irr??couvrable, ainsi que des factures pas compl??tement pay??e selectionn??es sur
   * l'??ch??ancier
   *
   * @param paymentSchedule Un ??ch??ancier de paiement
   * @throws AxelorException
   */
  @Transactional(rollbackOn = {Exception.class})
  public void notPassInIrrecoverable(PaymentSchedule paymentSchedule) throws AxelorException {
    paymentSchedule.setIrrecoverableStatusSelect(
        PaymentScheduleRepository.IRRECOVERABLE_STATUS_NOT_IRRECOUVRABLE);

    List<MoveLine> paymentScheduleLineRejectMoveLineList = new ArrayList<MoveLine>();

    for (PaymentScheduleLine paymentScheduleLine : paymentSchedule.getPaymentScheduleLineList()) {
      if (paymentScheduleLine.getRejectMoveLine() != null
          && paymentScheduleLine.getRejectMoveLine().getAmountRemaining().compareTo(BigDecimal.ZERO)
              > 0) {
        paymentScheduleLineRejectMoveLineList.add(paymentScheduleLine.getRejectMoveLine());
      }
    }

    for (MoveLine moveLine : paymentScheduleLineRejectMoveLineList) {
      this.notPassInIrrecoverable(moveLine, false);
    }

    for (Invoice invoice : paymentSchedule.getInvoiceSet()) {
      this.notPassInIrrecoverable(invoice);
    }
    paymentScheduleRepo.save(paymentSchedule);
  }
}
