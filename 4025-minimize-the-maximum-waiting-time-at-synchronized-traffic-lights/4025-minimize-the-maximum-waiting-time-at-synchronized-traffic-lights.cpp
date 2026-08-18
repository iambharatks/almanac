class Solution {
public:
    int minPenalty(int period, vector<int>& lights, vector<int>& arrivalTime) {
        int res = 0;
        sort(begin(lights),end(lights));
        int n = size(lights);
        for(int i : arrivalTime){
            int l = i%period;
            int x = lower_bound(begin(lights),end(lights),l+1)-begin(lights);
            if(x == n){
                res= max(res,(period-l));
            }
        }
        return res;
    }
};