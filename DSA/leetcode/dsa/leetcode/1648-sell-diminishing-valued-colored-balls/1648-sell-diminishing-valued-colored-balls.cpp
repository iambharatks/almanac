class Solution {
    const int MOD = 1e9+7;
public:
    int maxProfit(vector<int>& inventory, int order) {
        int res = 0;
        sort(rbegin(inventory),rend(inventory));
        int N = 0;
        int k = 1;
        int x = inventory[0];
        int n = size(inventory);
        int i = 1;
        while(order > 0){
            int y = (i == n)?0:inventory[i];
            long len = x-y;
            int rem = 0;
            if(order < len*i){
                len = order/i;
                rem = order%i;
                order -= rem;
            }
            res = (res + (2ll*x-len+1)*len/2ll*i + 1LL*(x-len)*rem)%MOD;
            order -= len*i;
            i++;
            x = y;
        }
        return res;
    }
};