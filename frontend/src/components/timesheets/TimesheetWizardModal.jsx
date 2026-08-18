import React, { useState, useEffect } from 'react';
import { Modal } from '../common/Modal';
import { Button } from '../common/Button';
import apiClient from '../../api/axios';
import { getProjects, getMyProjects } from '../../api/projectApi';
import { getMilestones } from '../../api/milestoneApi';
import { AlertCircle, Calendar, CheckCircle, Info, Flag, Award } from 'lucide-react';

const getDatesBetween = (start, end) => {
    const dates = [];
    let currentDate = new Date(start);
    const endDate = new Date(end);
    while (currentDate <= endDate) {
        dates.push(new Date(currentDate).toISOString().split('T')[0]);
        currentDate.setDate(currentDate.getDate() + 1);
    }
    return dates;
};

const calculateEndTime = (startTimeStr, hours) => {
    const [h, m] = (startTimeStr || '09:00').split(':').map(Number);
    const totalMinutes = h * 60 + (m || 0) + Math.round(Number(hours) * 60);
    const endH = Math.min(23, Math.floor(totalMinutes / 60));
    const endM = Math.min(59, totalMinutes % 60);
    return `${String(endH).padStart(2, '0')}:${String(endM).padStart(2, '0')}:00`;
};

const getDefaultPeriod = (freq = 'weekly') => {
    const today = new Date();
    const day = today.getDay(); // 0 is Sun, 1 is Mon
    const diffToMonday = today.getDate() - day + (day === 0 ? -6 : 1);
    const monday = new Date(new Date().setDate(diffToMonday));

    let start = new Date(monday);
    let end = new Date(monday);

    if (freq === 'weekly') {
        end.setDate(start.getDate() + 6); // Sunday
    } else if (freq === 'semi-weekly') {
        end.setDate(start.getDate() + 13);
    } else if (freq === 'monthly') {
        start = new Date(today.getFullYear(), today.getMonth(), 1);
        end = new Date(today.getFullYear(), today.getMonth() + 1, 0);
    }

    return {
        startStr: start.toISOString().split('T')[0],
        endStr: end.toISOString().split('T')[0]
    };
};

export const TimesheetWizardModal = ({ isOpen, onClose, onSuccess }) => {
    const [step, setStep] = useState(1);
    const [projects, setProjects] = useState([]);
    const [loadingProjects, setLoadingProjects] = useState(true);
    const [selectedProjectId, setSelectedProjectId] = useState('');
    const [projectMilestones, setProjectMilestones] = useState([]);
    const [loadingMilestones, setLoadingMilestones] = useState(false);
    const [selectedMilestoneId, setSelectedMilestoneId] = useState('');
    const [markAccomplished, setMarkAccomplished] = useState(false);
    const [frequency, setFrequency] = useState('weekly');
    const [startDate, setStartDate] = useState('');
    const [endDate, setEndDate] = useState('');
    const [dayDetails, setDayDetails] = useState([]);
    const [showCustomDate, setShowCustomDate] = useState(false);
    const [submitting, setSubmitting] = useState(false);

    // Fetch projects and set default date mapping on open
    useEffect(() => {
        if (isOpen) {
            setStep(1);
            setDayDetails([]);
            setFrequency('weekly');
            setShowCustomDate(false);
            setLoadingProjects(true);
            setSelectedMilestoneId('');
            setMarkAccomplished(false);

            const initialRange = getDefaultPeriod('weekly');
            setStartDate(initialRange.startStr);
            setEndDate(initialRange.endStr);

            const fetchProjects = async () => {
                try {
                    let data = await getMyProjects();
                    let list = Array.isArray(data) ? data : (data?.content || data?.data?.content || data?.data || []);
                    if (list.length === 0) {
                        data = await getProjects();
                        list = Array.isArray(data) ? data : (data?.content || data?.data?.content || data?.data || []);
                    }
                    setProjects(list);
                    if (list.length > 0) {
                        setSelectedProjectId(list[0].id);
                    } else {
                        setSelectedProjectId('');
                    }
                } catch (err) {
                    console.error("Error fetching projects for timesheet", err);
                    try {
                        const fallbackData = await getProjects();
                        const fallbackList = Array.isArray(fallbackData) ? fallbackData : (fallbackData?.content || fallbackData?.data?.content || fallbackData?.data || []);
                        setProjects(fallbackList);
                        if (fallbackList.length > 0) {
                            setSelectedProjectId(fallbackList[0].id);
                        }
                    } catch (e) {
                        console.error("Fallback getProjects failed", e);
                        setProjects([]);
                    }
                } finally {
                    setLoadingProjects(false);
                }
            };
            fetchProjects();
        }
    }, [isOpen]);

    // Fetch milestones whenever selected project changes
    useEffect(() => {
        if (!selectedProjectId) {
            setProjectMilestones([]);
            setSelectedMilestoneId('');
            return;
        }
        const fetchProjectMilestones = async () => {
            setLoadingMilestones(true);
            try {
                const data = await getMilestones({ projectId: selectedProjectId });
                const list = Array.isArray(data) ? data : (data?.content || data?.data || []);
                const matching = list.filter(m => (m.projectId === selectedProjectId || m.project?.id === selectedProjectId));
                setProjectMilestones(matching.length > 0 ? matching : list);
                if (matching.length > 0) {
                    const active = matching.find(m => m.status !== 'COMPLETED') || matching[0];
                    setSelectedMilestoneId(active ? active.id : '');
                } else {
                    setSelectedMilestoneId('');
                }
            } catch (err) {
                console.error("Failed to load project milestones", err);
                setProjectMilestones([]);
            } finally {
                setLoadingMilestones(false);
            }
        };
        fetchProjectMilestones();
    }, [selectedProjectId]);

    const handleFrequencyChange = (newFreq) => {
        setFrequency(newFreq);
        const range = getDefaultPeriod(newFreq);
        setStartDate(range.startStr);
        setEndDate(range.endStr);
    };

    const handleProceedToDetails = () => {
        if (!selectedProjectId) {
            alert('Please select an allotted project first.');
            return;
        }
        if (!startDate || !endDate) {
            alert('Please specify the date range.');
            return;
        }

        const dates = getDatesBetween(startDate, endDate);
        const initialDetails = dates.map(date => {
            const dayOfWeek = new Date(date).getDay();
            const isWeekend = dayOfWeek === 0 || dayOfWeek === 6; // Sunday or Saturday
            return {
                date,
                hours: isWeekend ? 0 : 8,
                extraHours: 0,
                milestoneId: selectedMilestoneId || '',
                tasks: ''
            };
        });
        setDayDetails(initialDetails);
        setStep(2);
    };

    const handleDetailChange = (index, field, value) => {
        const newDetails = [...dayDetails];
        newDetails[index] = { ...newDetails[index], [field]: value };
        setDayDetails(newDetails);
    };

    const handleSubmit = async () => {
        if (!selectedProjectId) {
            alert('Please select an allotted project');
            return;
        }

        const validDays = dayDetails.filter(d => (Number(d.hours) + Number(d.extraHours)) > 0);
        if (validDays.length === 0) {
            alert('Please enter working hours for at least one day.');
            return;
        }

        setSubmitting(true);
        try {
            for (const day of validDays) {
                const totalHours = Number(day.hours) + Number(day.extraHours);
                const startTime = "09:00:00";
                const endTime = calculateEndTime(startTime, totalHours);

                const chosenMilestone = projectMilestones.find(m => m.id === (day.milestoneId || selectedMilestoneId));
                const milestoneDesc = chosenMilestone ? `[Milestone: ${chosenMilestone.milestoneName}] ` : '';

                const payload = {
                    projectId: selectedProjectId,
                    milestoneId: (day.milestoneId || selectedMilestoneId) ? (day.milestoneId || selectedMilestoneId) : null,
                    workDate: day.date,
                    startTime: startTime,
                    endTime: endTime,
                    breakHours: 0,
                    description: `${milestoneDesc}${day.tasks || `Regular work on ${day.date} (${totalHours} hrs)`}`.trim()
                };

                const res = await apiClient.post('/timesheets', payload);
                const createdId = res?.data?.data?.id || res?.data?.id;

                if (createdId) {
                    try {
                        await apiClient.post(`/timesheets/${createdId}/submit`);
                    } catch (submitErr) {
                        console.warn("Timesheet auto-submit notice:", submitErr);
                    }
                }
            }

            // If contractor checked milestone accomplishment, mark milestone completed with 1 click
            if (markAccomplished && selectedMilestoneId) {
                try {
                    await apiClient.post(`/milestones/${selectedMilestoneId}/complete`);
                } catch (compErr) {
                    console.warn("Milestone complete notice:", compErr);
                }
            }

            if (onSuccess) onSuccess();
            onClose();
        } catch (error) {
            console.error('Error submitting timesheet:', error);
            const msg = error.response?.data?.message || error.response?.data?.error || 'Submission failed. Please check your selections.';
            alert(`Submission error: ${msg}`);
        } finally {
            setSubmitting(false);
        }
    };

    const selectedProjectObj = projects.find(p => p.id === selectedProjectId);
    const activeMilestoneObj = projectMilestones.find(m => m.id === selectedMilestoneId);

    const renderStep1 = () => (
        <div className="space-y-4">
            {loadingProjects ? (
                <div className="py-8 text-center text-sm text-slate-500">Loading your allotted projects...</div>
            ) : projects.length === 0 ? (
                <div className="rounded-lg bg-amber-50 p-4 border border-amber-200 dark:bg-amber-900/20 dark:border-amber-800 text-amber-800 dark:text-amber-200 text-sm space-y-2">
                    <div className="flex items-center font-semibold text-amber-900 dark:text-amber-100">
                        <AlertCircle className="h-5 w-5 mr-2 text-amber-600 flex-shrink-0" />
                        No Allotted Projects Found
                    </div>
                    <p>
                        Timesheet submission is strictly restricted to contractors with an active project allotment.
                    </p>
                    <p className="text-xs text-amber-700 dark:text-amber-300">
                        Please contact your Project Manager or Administrator to allot you to an active project before submitting timesheets.
                    </p>
                </div>
            ) : (
                <>
                    <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
                            Allotted Project *
                        </label>
                        <select
                            value={selectedProjectId}
                            onChange={(e) => setSelectedProjectId(e.target.value)}
                            className="w-full rounded-md border-slate-300 dark:border-slate-700 dark:bg-slate-800 dark:text-white px-3 py-2 border shadow-sm sm:text-sm focus:ring-primary-500 focus:border-primary-500 font-medium"
                        >
                            {projects.map(p => (
                                <option key={p.id} value={p.id}>
                                    {p.projectName || p.name} — {p.clientName || p.client || 'Client'} ({p.vendor?.vendorName || p.vendorName || 'Assigned Vendor'})
                                </option>
                            ))}
                        </select>
                    </div>

                    {/* Milestone Reached / Associated Deliverable Selector */}
                    <div>
                        <div className="flex items-center justify-between mb-1">
                            <label className="block text-sm font-medium text-slate-700 dark:text-slate-300">
                                Target Milestone Reached / In-Progress Deliverable
                            </label>
                            {loadingMilestones && (
                                <span className="text-[11px] text-slate-400">Loading milestones...</span>
                            )}
                        </div>
                        <select
                            value={selectedMilestoneId}
                            onChange={(e) => setSelectedMilestoneId(e.target.value)}
                            className="w-full rounded-md border-slate-300 dark:border-slate-700 dark:bg-slate-800 dark:text-white px-3 py-2 border shadow-sm sm:text-sm focus:ring-primary-500 focus:border-primary-500"
                        >
                            <option value="">-- General Project Work (No Milestone Linked) --</option>
                            {projectMilestones.map(m => (
                                <option key={m.id} value={m.id}>
                                    🚩 {m.milestoneName || m.name} ({m.completedDays || 0} of {m.assignedDays || 10} days completed - {m.completionPercentage || 0}%)
                                </option>
                            ))}
                        </select>
                        {activeMilestoneObj && (
                            <div className="mt-2 p-2.5 rounded-lg bg-indigo-50 dark:bg-indigo-950/40 border border-indigo-200 dark:border-indigo-800 text-xs text-indigo-900 dark:text-indigo-200 flex items-center justify-between">
                                <div className="flex items-center gap-2">
                                    <Flag className="h-4 w-4 text-indigo-600 shrink-0" />
                                    <span>
                                        <strong>{activeMilestoneObj.milestoneName}</strong>: {activeMilestoneObj.completedDays || 0} of {activeMilestoneObj.assignedDays || 10} days completed ({activeMilestoneObj.completionPercentage || 0}%)
                                    </span>
                                </div>
                                <span className="font-semibold text-indigo-700 dark:text-indigo-300">
                                    Due: {activeMilestoneObj.dueDate || 'Ongoing'}
                                </span>
                            </div>
                        )}
                    </div>

                    <div className="pt-1">
                        <h4 className="font-medium text-slate-800 dark:text-slate-200 mb-2">Timesheet Frequency</h4>
                        <div className="grid grid-cols-3 gap-2">
                            {['weekly', 'semi-weekly', 'monthly'].map(freq => (
                                <button
                                    key={freq}
                                    type="button"
                                    onClick={() => handleFrequencyChange(freq)}
                                    className={`py-2 px-3 text-xs font-medium rounded-lg border text-center transition-colors ${
                                        frequency === freq 
                                            ? 'bg-primary-50 border-primary-500 text-primary-700 dark:bg-primary-950/50 dark:border-primary-400 dark:text-primary-300 font-semibold shadow-sm'
                                            : 'bg-white border-slate-200 text-slate-700 hover:bg-slate-50 dark:bg-slate-800 dark:border-slate-700 dark:text-slate-300'
                                    }`}
                                >
                                    <span className="capitalize">{freq.replace('-', ' ')}</span>
                                </button>
                            ))}
                        </div>
                    </div>

                    {/* Auto-populated default date banner */}
                    <div className="rounded-lg bg-slate-50 dark:bg-slate-800/60 p-3 border border-slate-200 dark:border-slate-700 space-y-2 text-sm">
                        <div className="flex items-center justify-between">
                            <div className="flex items-center text-slate-700 dark:text-slate-300 font-medium text-xs">
                                <Calendar className="h-4 w-4 mr-1.5 text-primary-600" />
                                Allotted Period Mapping (Default)
                            </div>
                            <button
                                type="button"
                                onClick={() => setShowCustomDate(!showCustomDate)}
                                className="text-xs text-primary-600 hover:text-primary-700 dark:text-primary-400 underline font-medium"
                            >
                                {showCustomDate ? 'Use Default' : 'Custom Dates'}
                            </button>
                        </div>

                        <div className="text-slate-900 dark:text-white font-semibold text-sm">
                            {startDate} <span className="text-slate-400 font-normal">to</span> {endDate}
                        </div>

                        {showCustomDate && (
                            <div className="grid grid-cols-2 gap-3 pt-2 border-t border-slate-200 dark:border-slate-700">
                                <div>
                                    <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">Start Date</label>
                                    <input 
                                        type="date" 
                                        value={startDate} 
                                        onChange={(e) => setStartDate(e.target.value)} 
                                        className="w-full rounded-md border-slate-300 dark:bg-slate-800 dark:border-slate-700 dark:text-white px-2.5 py-1.5 border shadow-sm text-xs" 
                                    />
                                </div>
                                <div>
                                    <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">End Date</label>
                                    <input 
                                        type="date" 
                                        value={endDate} 
                                        onChange={(e) => setEndDate(e.target.value)} 
                                        className="w-full rounded-md border-slate-300 dark:bg-slate-800 dark:border-slate-700 dark:text-white px-2.5 py-1.5 border shadow-sm text-xs" 
                                    />
                                </div>
                            </div>
                        )}
                    </div>
                </>
            )}

            <div className="flex justify-end space-x-3 pt-4 border-t border-slate-100 dark:border-slate-800">
                <Button variant="outline" onClick={onClose}>Cancel</Button>
                <Button 
                    onClick={handleProceedToDetails} 
                    disabled={projects.length === 0 || !selectedProjectId}
                >
                    Proceed to Hours Entry
                </Button>
            </div>
        </div>
    );

    const renderStep2 = () => (
        <div className="space-y-4">
            <div className="flex items-center justify-between pb-2 border-b border-slate-100 dark:border-slate-800">
                <div>
                    <h4 className="font-semibold text-slate-900 dark:text-white text-sm">
                        {selectedProjectObj?.projectName || selectedProjectObj?.name || 'Project'}
                    </h4>
                    <p className="text-xs text-slate-500">
                        {startDate} to {endDate} ({dayDetails.length} days)
                        {activeMilestoneObj && ` • Milestone: ${activeMilestoneObj.milestoneName}`}
                    </p>
                </div>
                <Button variant="outline" size="sm" onClick={() => setStep(1)}>Change Project / Dates</Button>
            </div>

            <div className="space-y-2.5 max-h-72 overflow-y-auto pr-1">
                {dayDetails.map((day, idx) => (
                    <div key={day.date} className="p-3 border rounded-lg border-slate-200 dark:border-slate-700/80 bg-white dark:bg-slate-800/40 grid grid-cols-12 gap-3 items-center">
                        <div className="col-span-12 sm:col-span-3">
                            <span className="text-sm font-medium text-slate-800 dark:text-slate-200">
                                {new Date(day.date).toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' })}
                            </span>
                        </div>
                        <div className="col-span-4 sm:col-span-2">
                            <label className="block text-[11px] text-slate-500 mb-0.5">Hours*</label>
                            <input 
                                type="number" 
                                min="0" 
                                max="24" 
                                value={day.hours} 
                                onChange={(e) => handleDetailChange(idx, 'hours', e.target.value)} 
                                className="w-full rounded-md border-slate-300 dark:border-slate-700 dark:bg-slate-800 dark:text-white px-2 py-1 text-sm border focus:ring-primary-500" 
                                required 
                            />
                        </div>
                        <div className="col-span-4 sm:col-span-2">
                            <label className="block text-[11px] text-slate-500 mb-0.5">Extra</label>
                            <input 
                                type="number" 
                                min="0" 
                                max="24" 
                                value={day.extraHours} 
                                onChange={(e) => handleDetailChange(idx, 'extraHours', e.target.value)} 
                                className="w-full rounded-md border-slate-300 dark:border-slate-700 dark:bg-slate-800 dark:text-white px-2 py-1 text-sm border focus:ring-primary-500" 
                            />
                        </div>
                        <div className="col-span-12 sm:col-span-5">
                            <label className="block text-[11px] text-slate-500 mb-0.5">Tasks / Deliverable Note</label>
                            <input 
                                type="text" 
                                placeholder="e.g. Regular deliverables completion..." 
                                value={day.tasks} 
                                onChange={(e) => handleDetailChange(idx, 'tasks', e.target.value)} 
                                className="w-full rounded-md border-slate-300 dark:border-slate-700 dark:bg-slate-800 dark:text-white px-2.5 py-1 text-sm border focus:ring-primary-500" 
                            />
                        </div>
                    </div>
                ))}
            </div>

            {/* 1-Click Milestone Accomplishment Toggle */}
            {selectedMilestoneId && activeMilestoneObj && (
                <div className="flex items-center gap-2.5 p-3 rounded-lg bg-emerald-50 dark:bg-emerald-950/40 border border-emerald-200 dark:border-emerald-800 text-xs text-emerald-900 dark:text-emerald-200">
                    <input 
                        type="checkbox" 
                        id="markAccomplished" 
                        checked={markAccomplished} 
                        onChange={(e) => setMarkAccomplished(e.target.checked)} 
                        className="rounded border-emerald-300 text-emerald-600 focus:ring-emerald-500 h-4 w-4"
                    />
                    <label htmlFor="markAccomplished" className="font-semibold cursor-pointer flex items-center gap-1.5">
                        <Award className="h-4 w-4 text-emerald-600" />
                        Mark milestone "{activeMilestoneObj.milestoneName}" as Accomplished & Ended with this submission
                    </label>
                </div>
            )}

            <div className="flex justify-between pt-4 mt-2 border-t border-slate-200 dark:border-slate-700">
                <Button variant="outline" onClick={() => setStep(1)}>Back</Button>
                <Button onClick={handleSubmit} isLoading={submitting}>Submit Timesheet</Button>
            </div>
        </div>
    );

    return (
        <Modal 
            isOpen={isOpen} 
            onClose={onClose} 
            title="Timesheet & Milestone Submission" 
            className={step === 2 ? "max-w-3xl" : "max-w-lg"}
        >
            {step === 1 && renderStep1()}
            {step === 2 && renderStep2()}
        </Modal>
    );
};